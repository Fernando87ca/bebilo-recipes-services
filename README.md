# Bebilo Recipes Service

Servicio Kotlin/Ktor de consulta de recetas completas guardadas por cuenta. Submódulo de
`bebilo-services`, con remoto `git@github.com:Fernando87ca/bebilo-recipes-services.git`.

## Contrato HTTP

| Interna | Pública por gateway | Respuesta 200 |
| --- | --- | --- |
| GET `/v1/recipes` | `/recipes/v1/recipes` | `{"recipes":[RecipeDto,...]}` sin filtros ni límite |
| GET `/v1/recipe/{recipeId}` | `/recipes/v1/recipe/{recipeId}` | `RecipeDto` directo |

Bearer Firebase obligatorio. El UID procede únicamente de Auth `POST /v1/user-info`.
No se acepta identidad de cuenta en body o query. Firebase se resuelve después de autenticar.
IDs no blancos, opacos, sin UUID obligatorio. Codificar el ID como un segmento y no decodificar
dos veces. Inexistente o ajeno: mismo `404 NOT_FOUND`. Sin recetas: `200 {"recipes":[]}`.

[OpenAPI](src/main/resources/openapi.yaml) es el contrato ejecutable. Los GET no llaman a
AIconfig ni generan, enriquecen o modifican contenido. `Loading`, `Ready`, `Error` son estados
persistidos obligatorios, independientes de errores HTTP. Todos requieren una receta completa.

## Esquema Firestore

Colección: `users/{uid}/recipes/{documentId}`. Cada documento contiene los campos de `RecipeDto`
como mapas/arrays nativos, incluidos `status`, `summary`, `ingredients`, `cookingMethods`,
`instructions` e `imageMode`. Cada ingrediente requiere `category` persistido, string exacto
entre `vegetables`, `fruit`, `protein`, `dairy`, `cereal`, `seeds`, `oil_and_fats`, `sweet`,
sin null ni valor predeterminado. Aplica también a ingredientes libres y estados Loading/Error.
`catalogId` sigue siendo string opcional (IDs numéricos como texto u opacos), sin renumeración
ni inferencia de categorías desde el ID o el nombre. El futuro productor debe clasificar y
persistir categorías; los GET solo las leen. Categoría ausente, null, desconocida o de otro
tipo causa 500 INTERNAL_ERROR y falla el listado completo, sin reparar/omitir documentos.

Los campos opcionales pueden omitirse o contener null, excepto
`summary.tags`, que puede omitirse pero nunca ser null. Campos adicionales no se publican.

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

- 400 `VALIDATION_ERROR`: ID blanco o codificación inválida que llegue al servicio.
- 401 `UNAUTHORIZED`: bearer ausente/inválido/expirado o cuenta eliminada.
- 403 `FORBIDDEN`: Auth deniega uso general; no se usa para revelar recetas ajenas.
- 404 `NOT_FOUND`: receta inexistente/ajena o ruta ausente.
- 500 `INTERNAL_ERROR`: documento inválido, duplicados, fallo interno/configuración o fallo permanente de almacenamiento.
- 503 `SERVICE_UNAVAILABLE`: Auth indisponible o fallo temporal de Firestore.

Un fallo nunca se convierte en lista vacía o parcial. Las cancelaciones se propagan.

## Configuración y despliegue local

Env vars tienen prioridad sobre `local.properties`:
`APPLICATION_ENV`, `PORT`, `AUTH_SERVICE_BASE_URL`, `FIREBASE_PROJECT_ID`,
`GOOGLE_APPLICATION_CREDENTIALS`. Sin ruta explícita, se usan Application Default Credentials.
`FIRESTORE_EMULATOR_HOST` se usa solo en pruebas aisladas; no requiere credenciales reales.

```sh
./gradlew test -Penv=local
./gradlew build -Penv=local
./gradlew build -Penv=cloud
./gradlew distTar -Penv=local
docker --context rancher-desktop compose -p bebilo-recipes up -d --build
```

Compose usa la red externa `bebilo-gateway`, puerto directo 8084, puerto interno 8080,
Auth en `http://auth-service:8080/v1` y credenciales montadas en lectura.
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
El smoke arranca su propio emulador en 18082, Auth simulado interno, servicio en 18084 y
Traefik en 18088; requiere esos puertos libres. Limpia contenedores y la red aislada al terminar.
Para comprobar el despliegue habitual sin crear datos: `python3 scripts/local-smoke.py`.
No crea cuentas o recetas en Firebase real. Pruebas con dobles, SDK/emulador y gateway aislado
se registran por separado; no equivalen a integración móvil real.
