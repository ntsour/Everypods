# Revisión de traducciones: español, inglés y griego

Fecha: 18 de octubre de 2026. Rama de trabajo: `codex/librepods-eq-airpods5`.

## Alcance y resultado

Se revisaron las 617 entradas traducibles de pantalla y las 30 entradas de voz del catálogo original inglés y español. No existía catálogo griego: se ha creado completo. Las tres marcas no traducibles se heredan del catálogo base.

Además se extrajeron 49 textos de interfaz que estaban escritos directamente en Kotlin. El resultado es **666 entradas de pantalla y 30 entradas de voz** por idioma. En voz, dos entradas son plurales con formas singular y plural, no frases únicas.

Se corrigieron **229 entradas existentes en español** y **16 en inglés**, contando pantalla y voz. Los cambios anteriores de HRM, idioma, batería y cabecera se conservan. Las nuevas claves también tienen traducción en los otros nueve idiomas existentes para mantener su cobertura; esta revisión lingüística detallada se limita a EN, ES y EL.

El [inventario CSV](translation-review-es-en-el.csv) incluye las 696 entradas, el texto anterior EN/ES, el texto final EN/ES/EL y propuestas pendientes. Permite revisar cada cambio sin confundir las traducciones nuevas con las correcciones del catálogo anterior.

## Criterio editorial

- Español: trato de tú, instrucciones directas, mayúscula inicial en lugar de Mayúsculas En Cada Palabra. «Ajustes», «estuche», «auricular» y «pulsación» de manera consistente.
- Inglés: corregir errores y nombres inconsistentes sin cambiar el comportamiento descrito. «Conversation Awareness» sustituye a «Conversational Awareness» donde nombraba la misma función.
- Griego: vocabulario habitual para auriculares, terminología de Apple para sus funciones y mensajes completos en alfabeto griego. Se conservan nombres propios y siglas como EveryPods, AirPods, TICKR, Wahoo, Bluetooth, AACP, IRK y BPM.
- Emparejar no significa conectar. Transferir los AirPods a otro teléfono no significa perder el enlace Bluetooth de audio. Los avisos distinguen estos casos.
- Los porcentajes relativos conservan su significado: 40 % del volumen actual, no una reducción de 40 puntos.
- Los mensajes de voz evitan concordancias incorrectas al insertar duraciones. Por ejemplo, «Tiempo restante: %1$s» funciona con un segundo o varios minutos.
- Las claves, valores de preferencias, rutas y formatos de protocolo no se traducen. Tampoco se traduce el contenido de notificaciones procedente de otras aplicaciones.

## Errores claros corregidos

| Concepto | Texto español anterior | Español corregido | Inglés | Griego |
|---|---|---|---|---|
| Handover | Entrega | Transferencia | Handover | Εναλλαγή συσκευής |
| Auricular izquierdo | Brote izquierdo | Auricular izquierdo | Left earbud | Αριστερό ακουστικό |
| ANC | Perfiles del Congreso Nacional Africano | Perfiles de cancelación de ruido | ANC profiles | Προφίλ ακύρωσης θορύβου |
| Reconexión | Volver a conectar para durar | Reconectar al último dispositivo | Reconnect to last device | Επανασύνδεση στην τελευταία συσκευή |
| Otro dispositivo | Agregar compañero / dispositivo del mismo nivel | Añadir dispositivo / Otro dispositivo Android | Add device / Peer Android device | Προσθήκη συσκευής / Άλλη συσκευή Android |
| Permiso | subvención | Permitir | Grant | Να επιτρέπεται |
| Aportación | consejo | Aportar | Tip | Συνεισφορά |
| Apoyo a la aplicación | Soporte para todos los Pods | Apoyar EveryPods | Support EveryPods | Στήριξη του EveryPods |
| Control físico | Prensa única / Acciones de prensa | Una pulsación / Acciones por pulsación | Single press / Press actions | Ένα πάτημα / Ενέργειες πατήματος |
| Micrófono | Silenciar/activar el silencio | Silenciar/reactivar micrófono | Mute/Unmute | Σίγαση / ενεργοποίηση μικροφώνου |
| Conversación | Conciencia de la conversación | Detección de conversación | Conversation Awareness | Ανίχνευση συνομιλίας |
| HIIT | Trabajo / vuelta | Ejercicio / ronda | Work / Round | Άσκηση / Γύρος |
| Buscar ajustes | Configuración de búsqueda | Buscar en los ajustes… | Search settings… | Αναζήτηση ρυθμίσεων… |
| Medición HR | En directo | Midiendo | Live | Μέτρηση σε εξέλιξη |
| Señal de proximidad | Dirección / donde estas | Tendencia de la señal / Proximidad estimada | Signal trend / Estimated proximity | Τάση σήματος / Εκτιμώμενη εγγύτητα |

También se corrigieron frases completas que cambiaban el significado: «juega aquí» para iniciar audio, «caso» para el estuche, la descripción de conexión automática cuando se desactiva, la posibilidad de detener una grabación con la cámara y los mensajes de voz del temporizador.

El mensaje de error al cargar aportaciones ya no muestra instrucciones de configuración de Play Console al usuario. Se eliminó también del texto EN/ES/EL una cuota numérica de ElevenLabs que podía quedar desactualizada; ahora se remite al plan actual del proveedor.

## Vocabulario comprobado y propuestas pendientes

Reddit se usa como evidencia de uso real, no como autoridad técnica ni como encuesta representativa. Las etiquetas finales son decisiones editoriales; cuando no se encontró un ejemplo suficientemente preciso se indica expresamente.

### 1. Handover / automatic switching

En [esta conversación de r/airpods](https://www.reddit.com/r/airpods/comments/1p3qjds/i_love_my_airpods_pro_3_but_automatic_switching/) los participantes hablan de «automatic switching» al cambiar los AirPods entre dispositivos.

- **ES aplicado:** «Transferencia», según lo solicitado. No «Entrega».
- **EN propuesto:** «Device switching» como título y «Automatic switching» para la opción automática. De momento se conserva «Handover» en el título inglés.
- **EL aplicado:** «Εναλλαγή συσκευής», coherente con [Apple en griego](https://support.apple.com/el-gr/104988).
- El aviso de pérdida de conexión describe el enlace usado por la transferencia y su explicación aclara qué automatismos pueden fallar. No afirma que se haya perdido el audio de los AirPods.

### 2. Transparencia / sonido ambiente

En [r/AskArgentina](https://www.reddit.com/r/AskArgentina/comments/1bgj4om/) aparece «modo transparencia» al hablar de AirPods. [Apple España](https://support.apple.com/es-es/104979) utiliza «Sonido ambiente».

| Idioma | Texto actual | Propuesta alternativa |
|---|---|---|
| ES | Transparencia | **Sonido ambiente**; en la ayuda, «también llamado modo transparencia» |
| EN | Transparency | Mantener |
| EL | Διαφάνεια | Mantener |

**Pendiente de elección:** no se ha sustituido automáticamente «Transparencia» porque ambos usos son comprensibles y el ejemplo de Reddit es de español americano, no una prueba de preferencia mayoritaria en España.

### 3. Case, earbuds y controles

En [este hilo griego sobre una θήκη ακουστικών de Apple](https://www.reddit.com/r/greece/comments/1qwwhkw/) aparecen los términos cotidianos para estuche y auriculares. Se usan «θήκη» y «ακουστικό»; en español, «estuche» y «auricular».

Para el control físico, «pulsación» evita la traducción errónea de *press* como prensa. En las explicaciones españolas se usa «parte alargada del auricular», mientras el griego puede usar «στέλεχος». No se presentan esas expresiones como una preferencia demostrada por Reddit.

### 4. Gimnasio: ronda, parcial y temporizador

En [r/Gimnasio](https://www.reddit.com/r/Gimnasio/comments/1l3ydpr/) se utiliza «rondas» para circuitos HIIT. En [esta conversación griega sobre relojes](https://www.reddit.com/r/greece/comments/1wecq2v/) se distinguen cronómetro y cuenta atrás con «χρονόμετρο» y «αντίστροφη μέτρηση».

- HIIT: «Ejercicio / Descanso / Ronda» y «Άσκηση / Ανάπαυση / Γύρος».
- Cronómetro: «Parcial» para el registro de un intervalo; «Ενδιάμεσος χρόνος» en griego.
- **Opción editorial:** «Vuelta» también sería válida si la interfaz estuviera orientada exclusivamente a correr. Aquí se ha elegido «Parcial» porque el cronómetro se usa para entrenamientos generales. No se encontró una base suficiente en Reddit para afirmar que una opción sea mayoritaria.
- Cuenta atrás y cronómetro mantienen nombres distintos; no se traduce todo como «cronómetro».

### 5. Funciones de audio y frecuencia cardíaca en griego

Las [especificaciones griegas de AirPods Pro 3](https://support.apple.com/el-gr/125135) respaldan los nombres griegos de cancelación de ruido, detección de conversación y frecuencia cardíaca. Se usan «Ανίχνευση συνομιλίας» y «Καρδιακοί παλμοί» en sus pantallas. BPM se conserva como unidad breve; no se traduce el nombre del sensor TICKR.

No se encontró un hilo griego de Reddit suficientemente preciso sobre la etiqueta de HRM como para atribuirle una preferencia. Para ese término se emplea la documentación del fabricante.

### 6. Aportaciones, avisos y ajustes

«Aportar», «Lectura de notificaciones», «Avisos de voz» y «Horario de silencio» son correcciones de significado y claridad según la función real de la pantalla. No dependen de una votación de Reddit ni se presentan como tales. «Tip» puede significar consejo o aportación; en esta pantalla se realiza una aportación económica voluntaria.

## Textos que estaban fuera del sistema de traducciones

Se han localizado las categorías del menú, cabeceras, controles de pulsación, acciones del modo gimnasio, opciones del temporizador, avisos de actividad en segundo plano, estados de conexión, calibración y errores del buscador Bluetooth, resultados de prueba de voz, permisos y edición de perfiles.

Los resultados de búsqueda se traducen según el idioma actual. Se conservan los términos ingleses como alias adicionales y las rutas originales de navegación. Cambiar de idioma actualiza el índice mostrado.

Las etiquetas técnicas y descripciones de paquetes del visor de depuración antiguo todavía contienen inglés dentro de su analizador; los registros y nombres de protocolo se conservan para diagnóstico. La cobertura completa citada arriba corresponde al catálogo de recursos y a los textos de interfaz extraídos en esta revisión, no a una garantía de que cada cadena técnica del código esté traducida.

## Observaciones que requieren otra decisión

1. `workspace_use_description` afirma cumplir EN 352. Traducirlo no demuestra esa conformidad. Debe revisarse contra la implementación y los modelos compatibles antes de reformular la afirmación.
2. `ui_required_for_bluetooth_scannin` menciona Android 11 y anteriores, aunque la aplicación tiene mínimo Android 13. Es contenido antiguo que conviene revisar o eliminar; no basta con traducirlo.
3. Hay recursos de una versión previa de compartición HR por Wi-Fi que no se usan en la pantalla actual. Se han traducido para mantener el catálogo completo, sin volver a activarlos ni mezclar su explicación con TICKR o Bluetooth.
4. Las voces griegas del sistema dependen de las voces instaladas en cada teléfono. Añadir las frases griegas no instala por sí mismo un motor de voz.

## Comprobación y entrega

- Validación de cobertura, tipos de recurso, plurales y parámetros: completada para los once idiomas.
- Compilaciones debug y release completadas. Versión 1.0.4-rc10 (121) instalada como actualización en Pixel 10 y Xiaomi, sin borrar datos. La versión instalada se comprobó con el gestor de paquetes de ambos dispositivos.
- No se han ejecutado pruebas unitarias ni se ha cambiado el idioma personal del teléfono.
- Falta revisión visual y escucha por un hablante nativo de griego en el dispositivo; una compilación correcta no valida la naturalidad ni el ajuste de todos los textos a la pantalla.
- Las propuestas de nombres que siguen abiertas están señaladas arriba y en el CSV. No se han realizado commits ni push en esta revisión.
