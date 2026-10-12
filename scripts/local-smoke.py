#!/usr/bin/env python3
"""Read-only checks against the existing Rancher deployment; never creates Firebase data."""
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


def get(url, token=None, gateway=False):
    headers = {"Host": "api.bebilo.localhost"} if gateway else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


checks = 0
for prefix in ["auth", "baby", "profile", "aiconfig", "recipes"]:
    for resource in ["health", "openapi.yaml"]:
        status, body = get(f"http://localhost:8088/{prefix}/{resource}", gateway=True)
        assert status == 200 and body
        checks += 1
for resource in ["health", "openapi.yaml", "swagger"]:
    assert get(f"http://localhost:8084/{resource}")[0] == 200
    checks += 1
expected_contract = (Path(__file__).resolve().parents[1] / "src/main/resources/openapi.yaml").read_text()
for url, gateway in [("http://localhost:8084/openapi.yaml", False), ("http://localhost:8088/recipes/openapi.yaml", True)]:
    assert get(url, gateway=gateway) == (200, expected_contract)
    checks += 1
for base, gateway in [("http://localhost:8084", False), ("http://localhost:8088/recipes", True)]:
    for route in ["recipes", "recipe/recipe-001", "recipe/recipe-001/images/image-001"]:
        for token in [None, "invalid-smoke-token"]:
            status, body = get(f"{base}/v1/{route}", token, gateway)
            assert status == 401, (base, route, status)
            value = json.loads(body)
            assert value["code"] == "UNAUTHORIZED" and value["message"]
            checks += 1
for base, gateway in [("http://localhost:8084", False), ("http://localhost:8088/recipes", True)]:
    # Unauthenticated POST must fail before any Firebase write or LLM call.
    headers = {"Content-Type": "application/json"}
    if gateway: headers["Host"] = "api.bebilo.localhost"
    req = urllib.request.Request(base + "/v1/recipe", data=b"{}", headers=headers, method="POST")
    try:
        urllib.request.urlopen(req, timeout=10)
        raise AssertionError("POST unexpectedly allowed anonymous creation")
    except urllib.error.HTTPError as error:
        assert error.code == 401 and json.loads(error.read())["code"] == "UNAUTHORIZED"
    checks += 1
for recipe_id in ["one/two ?#%", "literal%2F", " padded ", "puré-你好", "a+b"]:
    encoded = urllib.parse.quote(recipe_id, safe="")
    status, body = get(f"http://localhost:8088/recipes/v1/recipe/{encoded}", "invalid-smoke-token", True)
    assert status == 401 and json.loads(body)["code"] == "UNAUTHORIZED"
    checks += 1
assert get("http://localhost:8088/swagger/", gateway=True)[0] == 200
checks += 1
status, initializer = get("http://localhost:8088/swagger/swagger-initializer.js", gateway=True)
assert status == 200 and "/recipes/openapi.yaml" in initializer
checks += 1
assert get("http://localhost:8088/recipes-other/health", gateway=True)[0] == 404
checks += 1
print(f"PASS: {checks} read-only deployment checks; no valid accounts, recipes or Firebase fixtures created.")
