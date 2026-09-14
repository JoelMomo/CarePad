# T1 — QA-47: foco al armar captura del stick

Continuación exclusiva de PR #50 desde `4f5f0f85c1229437a156e0d508fc794a0d1a8fb3`, base apilada fija `feature/single-apk-t1-controls-internal@0e0059fc7b7ab7e3332cb829ea512651331ded82`. Prevuelo GitHub y checkout: HEAD exacto, limpio, OPEN / DRAFT / merged=false / mergedAt=null / mergeable=true. C19 HOLD. PR #49 y #50 NO MERGE.

## Evidencia y atribución

[QA-47](https://app.notion.com/p/3dbf682468148183b5d7c90242da3c03) está CERRADA/FAIL. El [cierre formal](https://app.notion.com/p/3dbf682468148183b5d7c90242da3c03?d=3dbf68246814819fb0b2001c70d88650&pvs=42) conserva O2–O8 PASS, O1 utilizable con observación de parpadeo/transitorio HOME y O9 FAIL. Resto no ejecutado después del fallo. No se repite QA-47 ni se toca Thor.

Traza original conservada: `QA47-CarePadT1Focus-resume.txt`, 210.076 bytes, SHA-256 `462b0f6da2c7af71531d14f9b82fd19f1b1126ea30732d600567d2a619d5eded`. Entorno físico documentado: AYN Thor, Android 13 / SDK 33, mando device=10, APK #10330799112 SHA-256 `95a5fbb2e6f256ff43ca2b629121ca79ba15d0f7002b943514abb758be0bad7e`.

| t (ms) | Hecho en la traza física |
| --- | --- |
| 1519425 | LEFT_MOVE restaurado; target 22280941 enfocado, visible/habilitado; Keyboard, touch=false. |
| 1580472 | A UP activa Probar recorrido (`action-activate`, key=96/source=1281). |
| 1580494 | Ese mismo target pierde foco con enabled=false; el contenido pierde hasFocus. |
| 1580497 | HOME gana foco. |
| 1581003–1581004 | Llega el primer movimiento y lo consume controls-raw. |

El rail gana foco 506 ms antes del primer movimiento. Entre la activación y ese movimiento hay cero raw-motion. Por tanto, el escape sucede al armar; los movimientos posteriores consumidos no son su causa. La sustitución de acciones al observar movimiento ocurre después del escape.

En `StickMove`, Probar recorrido usa `enabled = !attemptArmed` y llama directamente a `startAttempt()`. Armar deshabilita el target enfocado; se invalida ese foco y el contenido pierde hasFocus. La recuperación inicial queda sin un destino interno explícito: en QA-47 termina en HOME; en la primera reproducción CI vuelve a Atrás después de perder el foco interno. La restauración de pantalla/paso no interviene porque esas claves no cambian y excluye captura armada. La corrección anterior de QA-46 se limitaba a `DigitalStep`; no cubría esta acción de sticks.

## Oráculo automatizado

El commit `ce854bc8aa9a4da6f41694933548f2b9e0fc2581` añade únicamente `focusedStickTryEntersCaptureBeforeAnyMotionWithoutGivingFocusToRail`; no modifica producción. [Android #393](https://github.com/JoelMomo/CarePad/actions/runs/34838528450) pasó 24 tests, pero su traza muestra la diferencia de precondición: la llegada por touch y recuperación D-pad pierde hasFocus al armar (`t=142267`) y el foco inicial vuelve a Atrás (`t=142270`), sin pasar por HOME. Ese verde no reproduce el destino del escape físico y no se considera RED causal.

El commit `d177a89cfa1db22ae2d4305ab58c17dedcb719e3` ajusta exclusivamente el test: confirma reposo mediante A, con foco de mando, y comprueba Probar recorrido enfocado por la restauración de LEFT_MOVE antes de armar, como en la traza física. La prueba utiliza Activity, shell y Controls reales; solo controla el catálogo del mando. Recorre los ocho controles digitales mediante UI e inputs Android. No establece directamente el paso ni llama a startAttempt desde el test.

El commit `8cc97c4dbf5042518f0a6768df279cc5ff663e7b` refuerza la continuidad de foco interno: el intervalo tampoco puede contener `content-focus ... hasFocus=false`. Así se detecta la pérdida causal aunque la recuperación automática vuelva a Atrás en el emulador y oculte el destino HOME observado en Thor. Esta condición protege el ancla interna estable durante la entrada, sin depender del destino de recuperación elegido por Android.

Tras A, exige captura activa y Probar deshabilitado. Las marcas `qa47-stick-left=…-start/before-first-motion` aíslan la entrada: exactamente una activación, cero motion-result, cero pérdidas de foco del contenido y cero callbacks de foco ganado por el rail, incluidos transitorios. Después exige foco visible en Atrás, A/B capturados sin salida, movimiento observado, ausencia de navegación mientras el stick sigue mantenido y respuesta inmediata del primer D-pad tras neutro. Repite esa secuencia en ambos sticks, que comparten `StickMove`.

Los 23 tests anteriores permanecen intactos para proteger O2–O8: orden HAT/KEY y segunda pulsación, A real y restauraciones, cruce espacial/L1, foco real de Probar digital, transferencia digital a Atrás, A/B capturados y liberación seguida de navegación. Los 250 ms del test respetan la exclusión de armado existente; no se cambia ningún tiempo del producto.

## RED causal y corrección mínima

[Android #395](https://github.com/JoelMomo/CarePad/actions/runs/34840779861), sobre `8cc97c4dbf5042518f0a6768df279cc5ff663e7b` sin cambios de producción, termina RED: 24 tests, 1 fallo, exclusivamente el oráculo nuevo en la continuidad de foco interno. Unit-tests, build y C1.3 #89 pasan. El intervalo izquierdo contiene una activación (`t=140593`), Probar deshabilitado y pérdida de hasFocus del contenido (`t=140595`), antes de cualquier movimiento. CI recupera Atrás en `t=140596`: demuestra la invalidación causal, no una reproducción del destino HOME. El destino HOME y sus 506 ms de adelanto sobre el movimiento están acreditados por la traza física. La [evidencia roja](https://github.com/JoelMomo/CarePad/actions/runs/34840779861/artifacts/10346396821) conserva ese contraste.

La corrección añade un FocusRequester al Atrás ya montado/habilitado de `StickMove`. Solo en Keyboard, la acción de Probar recorrido solicita foco en ese ancla antes de llamar a startAttempt. Al deshabilitar Probar, el contenido conserva foco: se evita la invalidación que dejaba el destino a la recuperación inicial. `StickMove` es compartido por ambos sticks; el oráculo comprueba ambos. Se registra `capture-entry-focus control=LEFT_STICK/RIGHT_STICK accepted=…` mediante la traza debug opt-in existente.

La ruta digital permanece intacta. El cambio de producción se limita a 15 inserciones y 2 eliminaciones en `StickMove`, sin reintentos, temporizadores, cambios de captura ni refactor de foco. Los A/B y movimientos durante captura siguen perteneciendo al puente raw; touch conserva su ruta. Se requiere CI completa verde y un nuevo artefacto antes de declarar candidata técnica.

La primera cualificación del código corregido (`83b562ae…`, Android #396 / C1.3 #90) se detuvo en setup-android, antes de compilar: `Failed to find package 'tools'`. Para ejecutar los gates se fija `packages: platform-tools` en las cuatro inicializaciones SDK de esos dos workflows; las instalaciones explícitas posteriores de plataforma, build tools y emulador se conservan. Es el parámetro [documentado por setup-android v3](https://github.com/android-actions/setup-android/tree/v3#additional-packages), cuyo valor por defecto incluye el paquete tools no disponible. Este ajuste de infraestructura no modifica el producto ni elimina gates.

## Límites de la candidata

La observación O1 y las observaciones UX secundarias de QA-47 (ancla en Atrás, dirección para continuar y resaltado del D-pad) permanecen registradas fuera de esta corrección. El criterio de observación del recorrido, captura raw, drenaje HAT/KEY, restauraciones generales y rail siguen fuera del cambio. La firma QA estable sigue siendo un follow-up separado: el reset autorizado para QA-47 no constituye autorización de reset para una QA futura.

La siguiente QA corresponde a CO11 sobre HEAD y APK nuevos cualificados. Debe comprobar entrada en captura del stick izquierdo antes del primer movimiento, captura/neutro/navegación posterior en ambos sticks y preservación de O2–O8; no reutilizar QA-47 como PASS del nuevo artefacto.
