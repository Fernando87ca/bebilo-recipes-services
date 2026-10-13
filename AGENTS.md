# Agents Guide

`recipe-services` consulta y crea recetas persistidas por cuenta Firebase. Mantener las capas
`api`, `domain`, `data`, `di`, `config` y `plugins`, DTOs separados de Domain y DI nativa de Ktor.

Rutas internas GET `/v1/recipes` y GET `/v1/recipe/{recipeId}`, públicas bajo `/recipes`.
Autenticar mediante Auth `/v1/user-info` antes de resolver Firestore. La persistencia es
`users/{uid}/recipes/{documentId}`; `summary.id` es la identidad pública opaca, independiente
del documentId. No exigir UUID ni decodificar de nuevo el parámetro ya decodificado por Ktor.

GET `/v1/recipes/events` (pública `/recipes/v1/recipes/events`) mantiene SSE por cuenta:
primer snapshot y altas/cambios de estado mediante listener Firestore, sin historial/replay.
Evento `recipe-status` con solo `id` público (`summary.id`) y `status`; heartbeat comentario 30 s.
Validar snapshots completos y duplicados antes de emitir; contenido sin cambio de estado y
eliminaciones no emiten. Preflight del mismo listener 10 s antes de abrir; errores iniciales JSON,
posteriores cierran stream. Cola 64 lotes sin descarte silencioso; retirar listener al cancelar/cerrar.

Los GET no crean, escriben, reparan ni generan contenido y no consultan AIconfig, Babies o Profile.
Conservar todos los campos persistidos y exigir `status` y `ingredients[].category` explícitos.
Category admite solo los ocho valores de OpenAPI, sin default, inferencia ni reparación. Documentos inválidos y IDs
duplicados son errores internos; no producir bibliotecas parciales o vacías para ocultarlos.
Actualizar OpenAPI y pruebas con cualquier cambio de contrato.

POST `/v1/recipe` consulta AI Configuration y Babies con el Bearer y persiste Created antes de
responder 200. El worker de aplicación usa un contexto fijo sin tokens, escribe Loading antes
de Koog y Ready/Error al terminar. Created/Loading/Error admiten instrucciones vacías; Ready
requiere contenido textual completo. Conservar ingredientes iniciales, categorías y métodos.
Los prompts españoles y schemas están en `src/main/resources/prompts/`; recompilar para cambiarlos.
No introducir reintentos, reparación LLM ni recuperación tras reinicio.

Las fotos generadas son opcionales para Ready, incluida la portada y todos los pasos/párrafos.
Storage conserva objetos privados con firebaseStorageDownloadTokens; el DTO entrega URLs HTTPS
sin caducidad automática. Conocer el enlace concede acceso sin Bearer (decisión aceptada para
fotos culinarias de IA). No hay endpoint de descarga en Recipes. Los GET no crean ni renuevan
URLs y no escriben metadatos. La migración offline requiere inventario previo, valida propietario
referencia y objetos, conserva tokens existentes y usa precondiciones contra escrituras concurrentes.
No registrar URLs ni tokens de descarga en logs/evidencias.
En desarrollo, la clave OpenAI se lee de `OPENAI_API_KEY` en `local.properties` ignorado.
En producción puede inyectarse desde Google Secret Manager mediante variable de entorno o
archivo indicado por `OPENAI_API_KEY_FILE` (con prioridad); nunca registrarla.
`STORAGE_EMULATOR_HOST` solo puede usarse con proyecto demo y Firestore Emulator.


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
