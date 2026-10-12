#!/usr/bin/env python3
"""Exercise real Firestore reads and both public GETs in a disposable local stack.

Prerequisites: Docker, Python 3, recipes-service:local Docker image. Never uses real Firebase.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "demo-bebilo-recipes-smoke"
EMULATOR_IMAGE = "gcr.io/google.com/cloudsdktool/google-cloud-cli@sha256:be2f0e582a94c9e06e7a384fe2b3b39d590eeb8da65a110eda991598c100fe80"
EMULATOR = "http://127.0.0.1:18082"
SERVICE = "http://127.0.0.1:18084"
GATEWAY = "http://127.0.0.1:18088"
DOCUMENTS = f"{EMULATOR}/v1/projects/{PROJECT}/databases/(default)/documents"
CATEGORIES = ["vegetables", "fruit", "protein", "dairy", "cereal", "seeds", "oil_and_fats", "sweet"]


def request(url, token=None, method="GET", data=None, gateway=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if gateway:
        headers["Host"] = "api.bebilo.localhost"
    req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def wait_for(url, gateway=False):
    for _ in range(100):
        try:
            if request(url, gateway=gateway)[0] == 200:
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(0.2)
    raise RuntimeError(f"Service did not start: {url}")


def firebase_value(value):
    if value is None:
        return {"nullValue": None}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, list):
        return {"arrayValue": {"values": [firebase_value(item) for item in value]}}
    return {"mapValue": {"fields": {key: firebase_value(item) for key, item in value.items()}}}


def fixture(recipe_id, index=0):
    instruction = {"type": "steps", "steps": [{"id": "s", "text": "Texto persistido", "image": {"url": "https://example.com/step.png"}}]}
    if index % 2:
        instruction = {"type": "narrative", "paragraphs": [{"id": "p", "text": "Uno.\nDos."}]}
    return {
        "status": ["Loading", "Ready", "Error"][index % 3],
        "summary": {"id": recipe_id, "title": "Título original", "babies": [{"id": "baby", "name": "Nil"}], "allergens": [], "tags": ["future_tag"]},
        "ingredients": [{"name": "Ingrediente", "allergens": [], "quantity": 12.5, "category": CATEGORIES[index % len(CATEGORIES)]}],
        "cookingMethods": ["future_method"], "instructions": instruction,
        "imageMode": ["none", "cover", "contextual"][index % 3], "description": None,
    }


class AuthStub(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        token = body.get("token")
        assert self.path == "/v1/user-info"
        assert self.headers.get("Authorization") == f"Bearer {token}"
        if token == "forbidden":
            status, value = 403, {"code": "FORBIDDEN", "message": "Forbidden"}
        elif token in ("a", "b", "empty"):
            status, value = 200, {"uid": f"smoke-{token}", "email": "fixture@example.invalid"}
        else:
            status, value = 401, {"code": "UNAUTHORIZED", "message": "Invalid"}
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(value).encode())

    def log_message(self, *_):
        pass


def main():
    subprocess.run(["docker", "image", "inspect", "recipes-service:local"], check=True, stdout=subprocess.DEVNULL)
    names = ["bebilo-recipes-smoke-firestore", "bebilo-recipes-smoke-traefik", "bebilo-recipes-smoke-auth", "bebilo-recipes-smoke-service"]
    network = "bebilo-recipes-smoke"
    network_created = False
    started = []
    checks = 0
    with tempfile.TemporaryDirectory(prefix="bebilo-recipes-smoke-") as directory:
        try:
            subprocess.run(["docker", "network", "create", network], check=True, stdout=subprocess.DEVNULL)
            network_created = True
            subprocess.run(["docker", "run", "-d", "--network", network, "--network-alias", "firestore", "--name", names[0], "-p", "127.0.0.1:18082:8080", EMULATOR_IMAGE,
                            "gcloud", "emulators", "firestore", "start", "--host-port=0.0.0.0:8080", f"--project={PROJECT}"], check=True, stdout=subprocess.DEVNULL)
            started.append(names[0])
            # A non-existent collection is an empty successful read in the emulator.
            wait_for(f"{DOCUMENTS}/users")
            auth_script = Path(directory) / "auth.py"
            stub_source = Path(__file__).read_text().split("class AuthStub", 1)[1].split("def main():", 1)[0]
            auth_script.write_text("import json\nfrom http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\nclass AuthStub" + stub_source + 'ThreadingHTTPServer(("0.0.0.0", 8080), AuthStub).serve_forever()\n')
            subprocess.run(["docker", "run", "-d", "--name", names[2], "--network", network, "--network-alias", "auth",
                            "-v", f"{auth_script}:/app/auth.py:ro", "python:3.13-alpine", "python", "/app/auth.py"], check=True, stdout=subprocess.DEVNULL)
            started.append(names[2])
            subprocess.run(["docker", "run", "-d", "--name", names[3], "--network", network, "--network-alias", "recipes",
                            "-p", "127.0.0.1:18084:8080", "-e", "PORT=8080", "-e", f"FIREBASE_PROJECT_ID={PROJECT}",
                            "-e", "FIRESTORE_EMULATOR_HOST=firestore:8080", "-e", "AUTH_SERVICE_BASE_URL=http://auth:8080/v1",
                            "recipes-service:local"], check=True, stdout=subprocess.DEVNULL)
            started.append(names[3])
            wait_for(f"{SERVICE}/health")
            config = Path(directory) / "routes.yaml"
            config.write_text("""http:
  routers:
    recipes:
      rule: Host(`api.bebilo.localhost`) && (Path(`/recipes`) || PathPrefix(`/recipes/`))
      service: recipes
      middlewares: [recipes-strip]
  middlewares:
    recipes-strip:
      stripPrefix:
        prefixes: [/recipes]
  services:
    recipes:
      loadBalancer:
        servers:
          - url: http://recipes:8080
""")
            subprocess.run(["docker", "run", "-d", "--name", names[1], "--network", network, "-p", "127.0.0.1:18088:80",
                            "-v", f"{config}:/etc/traefik/routes.yaml:ro", "traefik:v3",
                            "--entrypoints.web.address=:80", "--providers.file.filename=/etc/traefik/routes.yaml"], check=True, stdout=subprocess.DEVNULL)
            started.append(names[1])
            wait_for(f"{GATEWAY}/recipes/health", gateway=True)
            ids = ["one/two ?#%", "literal%2F", " padded ", "puré-你好", "a+b"] + [f"recipe-{n}" for n in range(20)]
            for index, recipe_id in enumerate(ids):
                fields = firebase_value(fixture(recipe_id, index))["mapValue"]["fields"]
                fields["createdAt"] = {"timestampValue": "2026-10-12T08:00:00.123456Z"}
                status, _ = request(f"{DOCUMENTS}/users/smoke-a/recipes/physical-{index}", method="PATCH", data={"fields": fields})
                assert status == 200
            fields = firebase_value(fixture("private-b"))["mapValue"]["fields"]
            assert request(f"{DOCUMENTS}/users/smoke-b/recipes/private", method="PATCH", data={"fields": fields})[0] == 200
            status, body = request(f"{GATEWAY}/recipes/v1/recipes?limit=1", "a", gateway=True)
            assert status == 200
            recipes = json.loads(body)["recipes"]
            assert len(recipes) == 25
            checks += 1
            assert {value["ingredients"][0]["category"] for value in recipes} == set(CATEGORIES)
            checks += 1
            for value in recipes:
                encoded = urllib.parse.quote(value["summary"]["id"], safe="")
                status, body = request(f"{GATEWAY}/recipes/v1/recipe/{encoded}", "a", gateway=True)
                assert status == 200, (encoded, status, body)
                assert json.loads(body) == value
                expected = fixture(value["summary"]["id"], ids.index(value["summary"]["id"]))
                assert value["ingredients"][0]["category"] == expected["ingredients"][0]["category"]
                assert value["createdAt"] == "2026-10-12T08:00:00.123456Z"
                checks += 1
            other = request(f"{GATEWAY}/recipes/v1/recipe/private-b", "a", gateway=True)
            absent = request(f"{GATEWAY}/recipes/v1/recipe/absent", "a", gateway=True)
            assert other == absent and other[0] == 404
            checks += 1
            assert json.loads(request(f"{GATEWAY}/recipes/v1/recipes", "b", gateway=True)[1])["recipes"][0]["summary"]["id"] == "private-b"
            assert json.loads(request(f"{GATEWAY}/recipes/v1/recipes", "empty", gateway=True)[1]) == {"recipes": []}
            checks += 2
            for token, expected in [(None, 401), ("invalid", 401), ("forbidden", 403)]:
                for path in ["recipes", "recipe/absent"]:
                    status, body = request(f"{GATEWAY}/recipes/v1/{path}", token, gateway=True)
                    assert status == expected
                    assert json.loads(body)["code"] in ("UNAUTHORIZED", "FORBIDDEN")
                    checks += 1
            assert request(f"{GATEWAY}/recipes/v1/recipe/%20%20", "a", gateway=True)[0] == 400
            checks += 1
            # Duplicate public IDs are corruption, even with different physical document IDs.
            assert request(f"{DOCUMENTS}/users/smoke-a/recipes/duplicate", method="PATCH", data={"fields": firebase_value(fixture(ids[0]))["mapValue"]["fields"]})[0] == 200
            for path in ["recipes", f"recipe/{urllib.parse.quote(ids[0], safe='')}"]:
                status, body = request(f"{GATEWAY}/recipes/v1/{path}", "a", gateway=True)
                assert status == 500 and json.loads(body)["code"] == "INTERNAL_ERROR"
                checks += 1
            assert request(f"{DOCUMENTS}/users/smoke-a/recipes/duplicate", method="DELETE")[0] == 200
            corrupt = fixture("corrupt"); del corrupt["status"]
            assert request(f"{DOCUMENTS}/users/smoke-a/recipes/corrupt", method="PATCH", data={"fields": firebase_value(corrupt)["mapValue"]["fields"]})[0] == 200
            for path in ["recipes", "recipe/corrupt"]:
                status, body = request(f"{GATEWAY}/recipes/v1/{path}", "a", gateway=True)
                assert status == 500 and json.loads(body)["code"] == "INTERNAL_ERROR"
                checks += 1
            assert request(f"{DOCUMENTS}/users/smoke-a/recipes/corrupt", method="DELETE")[0] == 200
            # Category errors must fail the full library and must never repair the stored document.
            for invalid in ["missing", None, "fish", "Vegetables", " ", 1]:
                corrupt = fixture("corrupt")
                if invalid == "missing":
                    del corrupt["ingredients"][0]["category"]
                else:
                    corrupt["ingredients"][0]["category"] = invalid
                document_url = f"{DOCUMENTS}/users/smoke-a/recipes/corrupt"
                assert request(document_url, method="PATCH", data={"fields": firebase_value(corrupt)["mapValue"]["fields"]})[0] == 200
                before = request(document_url)
                for path in ["recipes", "recipe/corrupt"]:
                    status, body = request(f"{GATEWAY}/recipes/v1/{path}", "a", gateway=True)
                    assert status == 500 and json.loads(body)["code"] == "INTERNAL_ERROR"
                    checks += 1
                assert request(document_url) == before
                checks += 1
            assert request(f"{GATEWAY}/recipes/openapi.yaml", gateway=True) == (200, (ROOT / "src/main/resources/openapi.yaml").read_text())
            checks += 1
            # These GETs do not depend on any AIconfig process; the isolated stack has none.
            print(f"PASS: {checks} checks; Firestore emulator + Auth stub + real service + Traefik. No real Firebase data used.")
        except Exception:
            for name in started:
                subprocess.run(["docker", "logs", "--tail", "25", name], check=False)
            raise
        finally:
            for name in reversed(started):
                subprocess.run(["docker", "rm", "-f", name], check=True, stdout=subprocess.DEVNULL)
            if network_created:
                subprocess.run(["docker", "network", "rm", network], check=True, stdout=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
