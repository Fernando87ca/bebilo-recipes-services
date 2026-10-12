# Agents Guide

`recipe-services` consulta recetas completas persistidas por cuenta Firebase. Mantener las capas
`api`, `domain`, `data`, `di`, `config` y `plugins`, DTOs separados de Domain y DI nativa de Ktor.

Rutas internas GET `/v1/recipes` y GET `/v1/recipe/{recipeId}`, públicas bajo `/recipes`.
Autenticar mediante Auth `/v1/user-info` antes de resolver Firestore. La persistencia es
`users/{uid}/recipes/{documentId}`; `summary.id` es la identidad pública opaca, independiente
del documentId. No exigir UUID ni decodificar de nuevo el parámetro ya decodificado por Ktor.

Los GET no crean, escriben, reparan ni generan contenido y no consultan AIconfig, Babies o Profile.
Conservar todos los campos persistidos y exigir `status` y `ingredients[].category` explícitos.
Category admite solo los ocho valores de OpenAPI, sin default, inferencia ni reparación. Documentos inválidos y IDs
duplicados son errores internos; no producir bibliotecas parciales o vacías para ocultarlos.
Actualizar OpenAPI y pruebas con cualquier cambio de contrato.

Validación funcional desde este directorio:

```sh
./gradlew test -Penv=local
./gradlew build -Penv=local
./gradlew build -Penv=cloud
FIRESTORE_EMULATOR_HOST=127.0.0.1:18080 ./gradlew integrationTest -Penv=local
./gradlew installDist distTar -Penv=local
docker build -t recipes-service:local .
python3 scripts/isolated-smoke.py
```

`integrationTest` requiere un emulador local. `isolated-smoke.py` arranca y limpia su propio
emulador, Auth simulado y Traefik. Nunca crear datos de prueba en Firebase real para este cambio.
No versionar `local.properties`, `secrets/`, tokens, credenciales ni imágenes personales.
