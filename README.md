# Bebilo Recipes Service

Servicio Kotlin/Ktor de consulta y creación asíncrona de recetas guardadas por cuenta. Submódulo de
`bebilo-services`, con remoto `git@github.com:Fernando87ca/bebilo-recipes-services.git`.

## Contrato HTTP

| Interna | Pública por gateway | Respuesta 200 |
| --- | --- | --- |
| GET `/v1/recipes/events` | `/recipes/v1/recipes/events` | SSE de estados por cuenta |
| GET `/v1/recipes` | `/recipes/v1/recipes` | `{"recipes":[RecipeDto,...]}` sin filtros ni límite |
| GET `/v1/recipe/{recipeId}` | `/recipes/v1/recipe/{recipeId}` | `RecipeDto` directo |
| POST `/v1/recipe` | `/recipes/v1/recipe` | `RecipeDto` inicial con `status: Created` |

Bearer Firebase obligatorio. El UID procede únicamente de Auth `POST /v1/user-info`.
No se acepta identidad de cuenta en body o query. Firebase se resuelve después de autenticar.
IDs no blancos, opacos, sin UUID obligatorio. Codificar el ID como un segmento y no decodificar
dos veces. Inexistente o ajeno: mismo `404 NOT_FOUND`. Sin recetas: `200 {"recipes":[]}`.

[OpenAPI](src/main/resources/openapi.yaml) es el contrato ejecutable. Los GET no llaman a
AIconfig ni generan, enriquecen o modifican contenido. `Created`, `Loading`, `Ready`, `Error` son estados persistidos obligatorios. Created/Loading/Error
admiten instrucciones todavía vacías; Ready exige instrucciones completas y contenido válido.
POST autentica, obtiene AI Configuration y bebés de la cuenta, crea el documento y devuelve 200
sin esperar al LLM. Las coroutines de aplicación siguen trabajando tras acabar o desconectarse
el HTTP. Cada POST crea una receta nueva; no hay recuperación ni idempotencia.

Body de creación: `babyIds`, `ingredients` y `cookingMethods` no vacíos; `title`, `servings` y
`additionalInstructions` opcionales. Ingredientes con categoría y alérgenos explícitos. El contexto
se captura una vez: edad, etapa, textura, restricciones y configuración. El principal propone ingredientes,
las herramientas analyze_allergens y find_ingredient_alternatives analizan y resuelven incompatibilidades,
y el principal redacta con ingredientes aprobados. El análisis final completo precede a persistencia e imágenes.
Todos los ingredientes completan alérgenos sin borrar los declarados cuando se mantienen. custom se
reclasifica; los incompatibles se sustituyen u omiten con viabilidad confirmada por ambos agentes.
Los cambios de ingredientes iniciales quedan en ingredientChanges y adaptationNotes. Sin identificación,
alternativa válida o verificación final, termina Error conservando la solicitud inicial.

Koog 1.3.0 usa el modelId seleccionado en todos los agentes. Los
prompts españoles y JSON Schemas están en `src/main/resources/prompts/`; se cargan al iniciar
sin contactar al proveedor y requieren recompilar. Responses usa esquema estricto, prefijo fijo,
clave derivada del prompt/schema y breakpoint explícito; se registran tokens totales/cacheados,
nunca prompts, tokens ni cuerpos de error del proveedor. No se garantizan hits de caché reales.

La generación guarda texto en Loading antes de intentar fotos. none no genera imágenes; single
permite como máximo una portada opcional; multiple permite portada e imágenes de procesos
selectivas en pasos o párrafos. El plan puede estar vacío o ilustrar un proceso sin portada.
Fotos fallidas se omiten y la receta termina Ready si el texto es válido. Sin imagen, el campo
opcional puede estar ausente o ser null, nunca lleva una URL vacía.

Los JPEG se guardan en `recipe-images/{uid}/{documentId}/{imageId}.jpg`, sin ACL pública,
con token aleatorio en `firebaseStorageDownloadTokens`. `coverImage.url` e `image.url` contienen
el enlace HTTPS directo a Firebase: no caduca automáticamente y cualquiera que lo conozca
puede descargarlo sin Bearer hasta revocar el token o borrar el archivo. Esta condición está
aceptada para imágenes culinarias generadas por IA. Los GET siguen autenticados, solo leen
URLs persistidas y no consultan Storage. La app no necesita renovar enlaces; no debe enviar
el Bearer de la API a Firebase. No hay ruta `/v1/recipe/{recipeId}/images/{imageId}`.

### Migración de enlaces antiguos

La distribución incluye dos comandos offline (no arrancan HTTP):

```sh
./build/install/recipe-services/bin/recipe-services --inventory-image-urls
./build/install/recipe-services/bin/recipe-services --migrate-image-urls
```

Ejecutar primero inventario con la configuración del despliegue. Devuelve solo contadores y
estado del bucket, nunca cuentas, URLs ni tokens. La aplicación valida todos los documentos
antes de escribir; referencias inexistentes, ajenas, URLs antiguas de otro origen, identidades
duplicadas o documentos inválidos se registran como incidencias (exit 2). Sin incidencias,
conserva tokens existentes o añade uno preservando otros metadatos; cambia solamente summary
e instructions mediante precondición updateTime. Storage usa precondición metageneration.
Puede repetirse: URLs ya migradas no cambian. Si Firestore falla después de guardar un token,
el siguiente intento conserva ese token. No modifica el estado ni timestamps de la receta.
`RECIPES_PUBLIC_BASE_URL` identifica exclusivamente el origen histórico permitido en la migración.
No retirar el servicio anterior si existen referencias antiguas pendientes de migrar.

La disponibilidad real del bucket y el inventario del despliegue se registran en
[la evidencia del cambio](../docs/changes/recipes-permanent-image-urls/implementation.md).

## Esquema Firestore

Colección: `users/{uid}/recipes/{documentId}`. Cada documento contiene los campos de `RecipeDto`
como mapas/arrays nativos, incluidos `status`, `summary`, `ingredients`, `cookingMethods`,
`instructions` e `imageMode`. Cada ingrediente requiere `category` persistido, string exacto
entre `vegetables`, `fruit`, `protein`, `dairy`, `cereal`, `seeds`, `oil_and_fats`, `sweet`,
`custom`, sin null ni valor predeterminado. custom identifica un ingrediente manual. Aplica también a ingredientes libres y estados Loading/Error.
`catalogId` sigue siendo string opcional (IDs numéricos como texto u opacos), sin renumeración
ni inferencia de categorías desde el ID o el nombre. El productor Koog debe clasificar y
persistir categorías; los GET solo las leen. Categoría ausente, null, desconocida o de otro
tipo causa 500 INTERNAL_ERROR y falla el listado completo, sin reparar/omitir documentos.

Los campos opcionales pueden omitirse o contener null, excepto
`summary.tags`, que puede omitirse pero nunca ser null. Campos adicionales no se publican. `generationContext` conserva solicitud/configuración/bebés
sin Bearer; `generationImages` guarda las referencias privadas y se actualiza con el contenido.

`summary.id` contiene la identidad pública no blanca y única dentro de la cuenta. El ID físico
puede ser un identificador automático de Firestore; no se usa como identidad de la receta ni
se deriva de él un ID faltante. Esto permite IDs públicos como `one/two ?#%`.

El listado lee toda la subcolección, con el orden de documentos que devuelve Firestore, sin
ordenación de negocio añadida. El individual usa `whereEqualTo("summary.id", recipeId)` dentro
de esa subcolección. Mantener habilitado el índice de campo simple de `summary.id`; no se
requieren índices compuestos. Firestore no impone unicidad de campos: los productores deben
mantenerla; ambos GET rechazan duplicados en sus resultados con 500.

Fechas opcionales: strings ISO 8601 con zona o Firestore Timestamp, convertido a ISO 8601
conservando el instante leído. Los GET nunca inventan fechas, estados, alérgenos ni contenido.
URLs de imagen: HTTP/HTTPS absolutas ya recuperables por la app; no se firman, descargan ni
verifican por red durante la lectura. No se crean colecciones vacías ni migraciones.

## Errores

Formato JSON `{code,message}`, sin detalles internos en la respuesta:

- 400 `VALIDATION_ERROR`: ID o solicitud de creación inválidos.
- 401 `UNAUTHORIZED`: bearer ausente/inválido/expirado o cuenta eliminada.
- 403 `FORBIDDEN`: Auth deniega uso general; no se usa para revelar recetas ajenas.
- 404 `NOT_FOUND`: receta inexistente/ajena o ruta ausente.
- 404 `BABY_NOT_FOUND`: bebé solicitado no disponible para esta cuenta.
- 502 `DEPENDENCY_ERROR`: respuesta inválida de AI Configuration o Babies.
- 500 `INTERNAL_ERROR`: documento inválido, duplicados, fallo interno/configuración o fallo permanente de almacenamiento.
- 503 `SERVICE_UNAVAILABLE`: Auth indisponible o fallo temporal de Firestore.

Un fallo nunca se convierte en lista vacía o parcial. Las cancelaciones se propagan.

## Configuración y despliegue local

Env vars tienen prioridad sobre `local.properties`:
`APPLICATION_ENV`, `PORT`, `AUTH_SERVICE_BASE_URL`, `FIREBASE_PROJECT_ID`,
`GOOGLE_APPLICATION_CREDENTIALS`, `AI_CONFIGURATION_SERVICE_BASE_URL`, `BABY_SERVICE_BASE_URL`,
`RECIPES_PUBLIC_BASE_URL`, `FIREBASE_STORAGE_BUCKET`, `OPENAI_API_KEY`, `OPENAI_API_KEY_FILE`, `OPENAI_BASE_URL`,
`OPENAI_IMAGE_MODEL`, `RECIPE_GENERATION_CONCURRENCY`. Ver `local.properties.example`.
Concurrencia predeterminada: 2; modelo de fotos: gpt-image-2.5-flare, calidad media, 1024×1024.
La espera queda en memoria y las recetas pueden seguir Created/Loading después de reiniciar.
Sin ruta explícita de credenciales Firebase, se usan Application Default Credentials.
`FIRESTORE_EMULATOR_HOST` se usa solo en pruebas aisladas; no requiere credenciales reales.

```sh
./gradlew test -Penv=local
./gradlew build -Penv=local
./gradlew build -Penv=cloud
./gradlew distTar -Penv=local
docker --context rancher-desktop compose -p bebilo-recipes up -d --build
```

Compose usa la red externa `bebilo-gateway`, puerto directo 8084, puerto interno 8080,
Auth/AI Configuration/Babies por DNS interno y credenciales montadas en lectura.
En desarrollo, `OPENAI_API_KEY` se configura en `local.properties`, ignorado y con permisos
0600; Compose lo carga mediante `env_file`. En producción se inyecta desde Google Secret
Manager como variable `OPENAI_API_KEY` o archivo indicado por `OPENAI_API_KEY_FILE`; si se
configura un archivo, tiene prioridad. La clave nunca se incluye en la imagen ni en documentación.
Los GET no resuelven la clave ni el cliente LLM. Solo se permite `STORAGE_EMULATOR_HOST` en
proyectos demo con Firestore Emulator; el smoke usa un doble GCS sin credenciales.
Gateway: base `http://localhost:8088`, Host `api.bebilo.localhost`, prefijo `/recipes`.
Health, OpenAPI y Swagger: `/recipes/health`, `/recipes/openapi.yaml`, `/recipes/swagger`.
Swagger central: `http://api.bebilo.localhost:8088/swagger/`.

## Validación aislada

```sh
docker run --rm --name bebilo-recipes-test-firestore -p 127.0.0.1:18080:8080 \
  gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators \
  gcloud emulators firestore start --host-port=0.0.0.0:8080 --project=demo-bebilo-recipes
FIRESTORE_EMULATOR_HOST=127.0.0.1:18080 ./gradlew integrationTest -Penv=local
./gradlew distTar -Penv=local
docker build -t recipes-service:local .
python3 scripts/isolated-smoke.py
```

El primer comando mantiene el emulador abierto; ejecutar los siguientes en otra terminal.
El smoke arranca su propio emulador en 18082, Auth/AI Configuration/Babies/OpenAI/GCS simulados internos, servicio en 18084 y
Traefik en 18088; requiere esos puertos libres. Limpia contenedores y la red aislada al terminar.
Para comprobar el despliegue habitual sin crear datos: `python3 scripts/local-smoke.py`.
No crea cuentas o recetas en Firebase real. Pruebas con dobles, SDK/emulador y gateway aislado
se registran por separado; no equivalen a integración móvil real.

## SSE de estados

GET `/v1/recipes/events` con Bearer observa todas las recetas de la cuenta autenticada.
Respuesta `text/event-stream`, caché `private, no-store`. Al conectar/reconectar envía un evento
por receta existente y después nuevas recetas y cambios de estado:

```text
event: recipe-status
data: {"id":"recipe-id","status":"Loading"}

```

`id` es `summary.id`; `status` admite Created/Loading/Error/Ready. El DTO solo contiene esos
campos. Ready/Error no cierran conexión. Cambios de texto, fotos o fechas sin cambio de estado
no emiten eventos; eliminaciones tampoco. Cuenta vacía mantiene conexión con comentario heartbeat
cada 30 segundos. La app puede recuperar el contenido completo mediante los GET existentes.

La fuente es un listener Firestore por conexión, también detecta escrituras desde otras instancias.
No hay historial ni ID SSE/replay de Last-Event-ID: pueden omitirse estados intermedios rápidos o
durante desconexiones. Reconectar vuelve a sincronizar el estado actual. Se valida el primer
snapshot antes del stream (timeout 10 s → 503); errores anteriores conservan JSON existente.
Documentos inválidos/IDs duplicados fallan sin resultados parciales. Errores después de abrir o
cola de 64 lotes llena cierran stream sin JSON; desconexión libera listener. Sin migraciones.

La generación analiza todos los ingredientes mediante `analyze_allergens`, resuelve incompatibilidades con `find_ingredient_alternatives` y verifica la receta final antes de Ready e imágenes. `ingredientChanges` registra sustituciones/omisiones de la solicitud; sus alérgenos finales se entregan incluso sin restricciones. [Especificación](../docs/changes/recipe-allergen-agents/spec.md).
