#!/usr/bin/env python3
"""Exercise real Firestore reads/writes and public recipe routes in a disposable local stack.

Prerequisites: Docker, Python 3, recipes-service:local Docker image. Never uses real Firebase.
"""
import json
import base64
import email.parser
import email.policy
import gzip
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


JPEG_BASE64 = "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAAqADAAQAAAABAAAAAgAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAAgACAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A98ooor+ez7w//9k="


class AuthStub(BaseHTTPRequestHandler):
    objects = {}
    calls = []
    jpeg = base64.b64decode(JPEG_BASE64)

    def json_response(self, value, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(value).encode())

    def object_metadata(self, name):
        return {"kind": "storage#object", "name": name, "bucket": "demo-recipes", "generation": "1", "metageneration": "1", "size": str(len(self.objects[name])), "contentType": "image/jpeg"}

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/stats":
            return self.json_response(self.calls)
        if parsed.path.startswith(("/storage/v1/b/demo-recipes/o/", "/download/storage/v1/b/demo-recipes/o/")):
            name = urllib.parse.unquote(parsed.path.split("/o/", 1)[1])
            if name not in self.objects:
                return self.json_response({"error": {"code": 404, "message": "missing"}}, 404)
            if urllib.parse.parse_qs(parsed.query).get("alt") == ["media"]:
                self.send_response(200); self.send_header("Content-Type", "image/jpeg"); self.end_headers()
                return self.wfile.write(self.objects[name])
            return self.json_response(self.object_metadata(name))
        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        if token not in ("a", "b", "empty"):
            return self.json_response({"code": "UNAUTHORIZED"}, 401)
        if parsed.path == "/v1/ai-configuration":
            return self.json_response({"instructionStyle": "narrative" if token == "b" else "steps", "photoMode": "multiple" if token == "b" else "single" if token == "empty" else "none", "modelId": "gpt-6-luna"})
        if parsed.path == "/v1/get-babies":
            return self.json_response([{"id": "baby-" + token, "name": "Fixture", "ageMonths": 8, "feedingStage": "Puré", "preferredTexture": "suave", "restrictions": ["sin leche"], "foodInterest": "", "description": ""}])
        self.json_response({}, 404)
    def do_POST(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size)); self.rfile.read(2)
            raw_body = b"".join(chunks)
        else:
            raw_body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.headers.get("Content-Encoding") == "gzip":
            raw_body = gzip.decompress(raw_body)
        body = {} if self.path.startswith("/upload/") else json.loads(raw_body)
        token = body.get("token")
        if self.path.startswith("/upload/storage/v1/b/demo-recipes/o"):
            # The SDK uses a metadata+binary multipart upload; emulate its real HTTP boundary.
            message = email.parser.BytesParser(policy=email.policy.default).parsebytes(
                ("Content-Type: " + self.headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + raw_body)
            parts = list(message.iter_parts())
            metadata = json.loads(parts[0].get_payload(decode=True))
            self.objects[metadata["name"]] = parts[1].get_payload(decode=True)
            return self.json_response(self.object_metadata(metadata["name"]))
        if self.path == "/v1/responses":
            assert self.headers["Authorization"] == "Bearer smoke-dummy-key"
            assert body["model"] == "gpt-6-luna"
            assert body["prompt_cache_options"]["mode"] == "explicit"
            assert body["input"][0]["content"][-1]["prompt_cache_breakpoint"] == {"mode": "explicit"}
            agent = body["text"]["format"]["name"]
            self.calls.append({"agent": agent, "model": body["model"]})
            time.sleep(0.15)  # Keep the actual LLM boundary asynchronous relative to the POST.
            user = json.loads(body["input"][-1]["content"][0]["text"])
            if agent == "composition":
                ingredients = user["request"]["ingredients"]
                if any(item["name"] == "FAIL" for item in ingredients):
                    return self.json_response({"error": {"message": "simulated"}}, 500)
                for item in ingredients:
                    if item.get("quantity") is None: item["quantity"] = 100.0
                    if item.get("unit") is None: item["unit"] = "g"
                style = user["settings"]["instructionStyle"]
                instructions = {"type": "steps", "steps": [{"id": "s1", "text": "Cocer al vapor"}, {"id": "s2", "text": "Dejar reposar"}]} if style == "steps" else {"type": "narrative", "paragraphs": [{"id": "p1", "text": "Cocer al vapor y triturar."}, {"id": "p2", "text": "Servir."}]}
                output = {"title": user["request"].get("title") or "Patata al vapor", "ingredients": ingredients, "cookingMethods": user["request"]["cookingMethods"], "instructions": instructions, "description": "Receta de prueba", "preparationMinutes": 5, "cookingMinutes": 10, "servings": user["request"].get("servings") or 1, "storageNotes": None, "adaptationNotes": None, "recommendedAgeMonths": 8, "totalMinutes": 15, "texture": "Puré", "tags": []}
            else:
                recipe = user["recipe"]
                targets = recipe["instructions"].get("steps", recipe["instructions"].get("paragraphs"))
                output = {"images": [{"instructionId": None, "prompt": "PHOTOFAIL" if any(i["name"] == "PHOTOFAIL" for i in recipe["ingredients"]) else "Portada", "altText": "Patata"}]}
                if user["photoMode"] == "multiple":
                    output["images"].append({"instructionId": targets[0]["id"], "prompt": "Proceso", "altText": "Proceso"})
            return self.json_response({"id": "response-smoke", "created_at": 1791792000, "model": body["model"], "object": "response", "parallel_tool_calls": False, "status": "completed", "text": {"format": {"type": "text"}}, "output": [{"id": "message-smoke", "type": "message", "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": json.dumps(output), "annotations": []}]}], "usage": {"input_tokens": 1200, "output_tokens": 100, "total_tokens": 1300, "input_tokens_details": {"cached_tokens": 1024}, "output_tokens_details": {"reasoning_tokens": 0}}})
        if self.path == "/v1/images/generations":
            assert body["model"] == "gpt-image-2.5-flare" and body["output_format"] == "jpeg"
            self.calls.append({"agent": "image", "model": body["model"]})
            if "PHOTOFAIL" in body["prompt"]:
                return self.json_response({"error": {"message": "image failed"}}, 500)
            return self.json_response({"data": [{"b64_json": JPEG_BASE64}]})
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
            stub_source = Path(__file__).read_text().split("JPEG_BASE64 =", 1)[1].split("def main():", 1)[0]
            auth_script.write_text("import json, time, urllib.parse, base64, email.parser, email.policy, gzip\nfrom http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\nJPEG_BASE64 =" + stub_source + 'ThreadingHTTPServer(("0.0.0.0", 8080), AuthStub).serve_forever()\n')
            subprocess.run(["docker", "run", "-d", "--name", names[2], "--network", network, "--network-alias", "auth",
                            "-v", f"{auth_script}:/app/auth.py:ro", "python:3.13-alpine", "python", "/app/auth.py"], check=True, stdout=subprocess.DEVNULL)
            started.append(names[2])
            dummy_secret = Path(directory) / "openai-key"
            dummy_secret.write_text("smoke-dummy-key\n")
            dummy_secret.chmod(0o600)
            subprocess.run(["docker", "run", "-d", "--name", names[3], "--network", network, "--network-alias", "recipes",
                            "-p", "127.0.0.1:18084:8080", "-e", "PORT=8080", "-e", f"FIREBASE_PROJECT_ID={PROJECT}",
                            "-e", "FIRESTORE_EMULATOR_HOST=firestore:8080", "-e", "AUTH_SERVICE_BASE_URL=http://auth:8080/v1",
                            "-e", "AI_CONFIGURATION_SERVICE_BASE_URL=http://auth:8080/v1", "-e", "BABY_SERVICE_BASE_URL=http://auth:8080/v1",
                            "-e", "OPENAI_BASE_URL=http://auth:8080/v1", "-e", "OPENAI_API_KEY_FILE=/run/secrets/openai-key",
                            "-v", f"{dummy_secret}:/run/secrets/openai-key:ro", "-e", "FIREBASE_STORAGE_BUCKET=demo-recipes",
                            "-e", "STORAGE_EMULATOR_HOST=http://auth:8080", "-e", "RECIPES_PUBLIC_BASE_URL=http://api.bebilo.localhost:18088/recipes",
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
            assert request(f"{DOCUMENTS}/users/smoke-a/recipes/corrupt", method="DELETE")[0] == 200
            # POST and asynchronous generation with real Koog, Firebase SDKs and HTTP doubles.
            def create(token, ingredient="Patata"):
                body = {"babyIds": ["baby-" + token], "ingredients": [{"name": ingredient, "category": "vegetables", "allergens": []}], "cookingMethods": ["steam"]}
                status, result = request(f"{GATEWAY}/recipes/v1/recipe", token, "POST", body, gateway=True)
                assert status == 200, (status, result)
                value = json.loads(result)
                assert value["status"] == "Created"
                return value

            def finished(token, recipe_id):
                seen = set()
                for _ in range(100):
                    status, result = request(f"{GATEWAY}/recipes/v1/recipe/{recipe_id}", token, gateway=True)
                    assert status == 200, (status, result)
                    value = json.loads(result); seen.add(value["status"])
                    if value["status"] in ("Ready", "Error"):
                        return value, seen
                    time.sleep(0.05)
                raise AssertionError("Generation did not finish")

            for token, style in [("a", "steps"), ("b", "narrative")]:
                created = create(token); checks += 1
                generated, seen = finished(token, created["summary"]["id"])
                assert generated["status"] == "Ready" and generated["instructions"]["type"] == style
                assert "Loading" in seen
                assert generated["ingredients"][0]["name"] == "Patata"
                checks += 2
                assert request(f"{GATEWAY}/recipes/v1/recipe/{created['summary']['id']}", "b" if token == "a" else "a", gateway=True)[0] == 404
                checks += 1
                if token == "b":
                    images = [generated["summary"]["coverImage"], generated["instructions"]["paragraphs"][0]["image"]]
                    assert generated["instructions"]["paragraphs"][1].get("image") is None
                    for image in images:
                        image_path = urllib.parse.urlparse(image["url"]).path
                        req = urllib.request.Request(GATEWAY + image_path, headers={"Authorization": "Bearer b", "Host": "api.bebilo.localhost"})
                        with urllib.request.urlopen(req, timeout=10) as response:
                            assert response.headers["Content-Type"] == "image/jpeg"
                            assert response.headers["Cache-Control"] == "private, no-store"
                            assert response.read() == AuthStub.jpeg
                        assert request(GATEWAY + image_path, "a", gateway=True)[0] == 404
                        assert request(GATEWAY + image_path, gateway=True)[0] == 401
                        checks += 3
            failed = create("a", "FAIL")
            assert finished("a", failed["summary"]["id"])[0]["status"] == "Error"
            checks += 2
            photo_failed = create("empty", "PHOTOFAIL")
            value, _ = finished("empty", photo_failed["summary"]["id"])
            assert value["status"] == "Ready" and value["summary"].get("coverImage") is None
            checks += 2
            stats = json.loads(subprocess.check_output(["docker", "exec", names[2], "python", "-c", "import urllib.request; print(urllib.request.urlopen('http://localhost:8080/stats').read().decode())"]))
            assert sum(c["agent"] == "composition" for c in stats) == 4
            assert sum(c["agent"] == "visual-planning" for c in stats) == 2
            assert sum(c["agent"] == "image" for c in stats) == 3
            checks += 3
            invalid = {"babyIds": ["baby-b"], "ingredients": [{"name": "Patata", "category": "vegetables", "allergens": []}], "cookingMethods": ["steam"]}
            assert request(f"{GATEWAY}/recipes/v1/recipe", "a", "POST", invalid, gateway=True)[0] == 404
            checks += 1
            listed = request(f"{DOCUMENTS}/users/smoke-b/recipes")[1]
            for document in json.loads(listed)["documents"]:
                if "generationContext" in document["fields"]:
                    assert "smoke-dummy-key" not in json.dumps(document["fields"])
                    assert document["name"].rsplit("/", 1)[1] != generated["summary"]["id"]
                    checks += 1
            print(f"PASS: {checks} checks; real Recipes + Koog + Firebase SDKs + Firestore Emulator + Traefik; Auth/AI Configuration/Babies/OpenAI/GCS doubles. No real Firebase or LLM calls.")
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
