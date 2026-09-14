# T1 — escape al entrar en captura (QA-46)

Continuación de PR #50 desde `4c9096a3bf8aef0827387d251679ab6500bbc997`, sobre `feature/single-apk-t1-controls-internal@0e0059fc7b7ab7e3332cb829ea512651331ded82`. Prevuelo GitHub: OPEN / DRAFT / merged=false / mergedAt=null / mergeable=true; checkout limpio en la rama de PR #50. PR #49 y #50 NO MERGE; C19 HOLD.

## Evidencia física conservada

[QA-46](https://app.notion.com/p/3dbf682468148144923aca219db25338) permanece CERRADA/FAIL por el mandato CT6/CO11. Sus campos de preparación todavía muestran Preparada/Pendiente; los comentarios de ejecución registran el FAIL reproducido dos veces y la devolución formal. No se modifica ni se repite la ficha para recrear un vídeo no disponible.

Traza local original: `C:\Users\joele\Downloads\QA46-CarePadT1Focus.txt`, SHA-256 `8ffcb0fdd8d7ae12ff719a137f7a534c79374c80c51ac90269fd61a8bdf23603`.

HECHO: a las 04:15:01.105, device=10/source=1281, A UP produce `action-activate`. A las 04:15:01.118 el target 4374857 notifica `isFocused=false enabled=false` y el contenido pierde `hasFocus`. A las 04:15:01.123 `rail-focus destination=HOME isFocused=true`. Android y Compose ya estaban en Keyboard/touch=false: no es el defecto anterior de entrada desde Touch.

HECHO de código: `DigitalStep` deshabilita el botón activo con `enabled = !attemptArmed`; `startAttempt()` cambia `attemptArmed` a true. Al perder su target activo, Compose limpia/reinicia el foco. La restauración ya validada para cambios de pantalla/paso no se ejecuta: esas claves no cambian y, además, excluye captura armada. El test anterior iniciaba cada intento con touch; no tenía foco de mando sobre el botón que se deshabilita.

## Oráculo añadido

`focusedTryActionEntersCaptureWithoutGivingFocusToRail` abre la UI real, obtiene foco visible/habilitado en Probar este control mediante D-pad y pulsa A por Activity. Exige captura armada, foco en una acción interna estable y ausencia de foco en el rail. Inspecciona todos los callbacks `rail-focus` entre marcas de traza, para detectar también escapes transitorios. Después verifica B capturado sin salida, A observado sin segunda activación y navegación después de liberar el gesto.

Los 22 tests previos se conservan: primera/segunda dirección tras touch, HAT/KEY, A válido/cancelado/repetido, transiciones, rail espacial/L1, targets retirados y captura por touch. La nueva prueba utiliza el mismo tiempo de exclusión de 250 ms del producto; no se añade ningún temporizador de producción.

La nueva candidata requiere CI relevante verde sobre un HEAD nuevo y un nuevo artefacto. El resultado físico de QA-46 no se reetiqueta ni se hereda como PASS completo. CO11 decidirá la siguiente QA, exclusiva del nuevo HEAD/APK.

## Corrección acotada

Solo se modifica la acción digital Probar este control. Antes de `startAttempt()` y solo en Keyboard, se transfiere el foco sincrónicamente al botón Atrás ya montado/habilitado de ese paso. Después se arma captura y Probar queda deshabilitado. Así la invalidación del botón ya no invalida el target enfocado ni provoca el foco inicial del rail. No se espera a una recomposición para devolver foco que ya escapó.

La captura raw sigue consumiendo A/B del mando seleccionado; el nuevo test verifica que Atrás no se activa con B capturado ni con el A observado. El touch no solicita esa transferencia. No cambia el shell, el drenaje, el manejo general de A, la restauración de pantallas ya validada ni los tiempos de captura. No se modifica main ni PR #49.

Para la siguiente QA que decida CO11: usar exclusivamente el HEAD/APK nuevos cualificados; comprobar foco visible en Probar → A → captura activa con ancla interna, sin expansión ni foco en Inicio. Correlacionar `action-activate`, `capture-entry-focus accepted=true`, `action-focus` y `rail-focus`; no debe aparecer `rail-focus ... isFocused=true` como efecto de armar. Probar B durante captura, A observado, UP y siguiente dirección. Repetir las comprobaciones parciales de QA-46 sobre la nueva candidata, sin reabrir QA-46. La traza debug se conserva opt-in y release no registra detalles.