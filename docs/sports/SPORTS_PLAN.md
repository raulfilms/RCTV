# RCTV · Deportes: plan y reglas

Este documento recoge cómo tiene que funcionar la parte de deportes de RCTV. Es la referencia para
construir las páginas que faltan, empezando por la página de cada equipo. Se trabaja **paso a paso**:
cada paso se entrega y se prueba antes de empezar el siguiente.

---

## 1. La regla principal: todo sale del IPTV y del EPG

La página de **Sports**, la de **Leagues**, la de **Teams** y la de **cada equipo** están conectadas al
**IPTV** y a su **EPG** (la guía). La señal de cualquier juego o evento que se vea en la app sale de
ahí: de los canales del IPTV del usuario y de lo que su EPG dice que están pasando.

- **ESPN** solo pone los **datos**: marcadores, horarios, estado del juego, logos, colores y nombres.
- **IPTV + EPG** ponen la **señal**: qué canal pasa el juego y su stream.

## 2. Cómo está hoy

Ya están hechos:

| Página | Archivo | Qué tiene |
|---|---|---|
| Sports | `ui/screens/sports/SportsScreen.kt`, `SportsHome.kt` | Banner, Continue Watching, Live Games, Upcoming Games, Picked For You, Top Leagues, Teams, Sports Talk, My Sports, My Teams |
| Teams | `ui/screens/sports/TeamsBrowseScreen.kt` | Banner "TEAMS", filtros por liga, My Teams y los equipos de cada liga en filas hacia abajo |
| Leagues | `ui/screens/sports/LeaguesBrowsePage.kt` | Banner "LEAGUES" y todas las ligas en cuadrícula: NFL, NBA, MLB, NHL, MLS, fútbol, UFC, F1, Tennis |
| Juego | `ui/screens/sports/GameDetailScreen.kt` | Página de un juego |
| Liga | `ui/screens/sports/LeagueDetailScreen.kt` | Página de una liga |

**Lo que falta:** hoy las páginas de deportes **no buscan la señal en el IPTV ni en el EPG**. Los
juegos salen de ESPN (`data/remote/espn/EspnSportsClient.kt`) y "Watch Live" abre la página del
juego, pero no encuentra el canal que lo está pasando. Esa conexión es lo que hay que hacer (punto 3).

## 3. Encontrar el canal que pasa un juego (IPTV + EPG)

La guía ya está cargada y en caché en `ui/screens/livetv/LiveTvGuideLoader.kt`
(`guide: StateFlow<LiveGuideSnapshot?>`), con:

- `channels: List<LiveTvChannel>`: los canales del IPTV.
- `epgByChannel: Map<String, List<EpgProgram>>`: los programas de la guía por canal.
- `resolvePlaybackUrl(channel, sources)`: el stream del canal para reproducirlo.

Para cada juego (`SportsEvent`) se buscan en el EPG los programas (`EpgProgram`) que lo están pasando:

1. **Horario:** el programa coincide con la hora del juego. Si está en vivo, el programa está en el
   aire ahora (`isAiringAt(now)`); si es más tarde, el programa empieza alrededor de `startTimeMs`.
2. **Equipos:** el título, el subtítulo o la descripción del programa nombra a los **dos equipos**, o
   a uno de ellos y a la liga. Se comparan el nombre completo ("New York Yankees"), el nombre corto
   ("Yankees") y la abreviatura ("NYY"), sin acentos ni mayúsculas.
3. **Deporte:** si el programa tiene categorías, que sean de deportes (`categories`).
4. **Orden:** primero los programas marcados en vivo (`isLive`), luego los que nombran a los dos
   equipos, luego los canales del país del usuario y sus canales favoritos (Custom).

El resultado es la lista de canales que pasan el juego. Puede haber más de uno: el nacional, el
local, uno en español, etc. Si hay varios, el usuario escoge.

## 4. El botón del juego: Watch Live o Watch Replay

| Estado del juego | ¿Está en el IPTV/EPG? | Botón | Qué hace |
|---|---|---|---|
| En vivo | Sí | **Watch Live** | Reproduce el canal del IPTV que lo pasa (si hay varios, se escoge) |
| Por empezar | Sí | **Watch Live** cuando empiece | Antes de empezar, muestra a qué hora y en qué canal |
| Terminado | **No**, ya no está en el IPTV ni en el EPG | **Watch Replay** | Busca en YouTube los highlights del juego y los reproduce (punto 5) |
| Terminado | Sí, todavía sale en la guía (por ejemplo, una retransmisión) | **Watch Live** | Reproduce el canal de la guía |

La regla de Raúl: **si el juego ya terminó y ya no se está viendo en el IPTV ni está en el EPG, el
botón dice Watch Replay.**

### Dónde aplica

- Los **juegos y eventos guardados** (Add to Library, `SportsLibraryStore.rememberEvent`).
- Los juegos de los **equipos guardados** (My Teams).
- Dondequiera que salga un juego: la página de cada equipo, la de cada liga, la del juego y la Library.

## 5. Watch Replay: highlights de YouTube

Cuando el juego terminó y ya no está en la guía, se busca su replay en YouTube, por ejemplo los
highlights:

- **Búsqueda:** `"{visitante} vs {local} highlights {fecha}"`, por ejemplo
  `"Red Sox vs Yankees highlights October 15, 2026"`.
- **Preferir canales oficiales:** NFL, NBA, MLB, NHL, MLS, la liga del partido, ESPN, ESPN FC, etc.
- **Que sea de ese juego:** publicado después de que empezó el juego y con los dos equipos en el título.
- **Reproducir dentro de la app:** la app ya reproduce videos de YouTube para los trailers
  (`data/trailer/InAppYouTubeExtractor.kt`), así que el replay puede usar lo mismo.

**Por decidir:** cómo se busca en YouTube. Si se usa la YouTube Data API, la clave va **solo** en el
secreto de GitHub `LOCAL_PROPERTIES_BASE64`, igual que la de TMDB, **nunca en el código ni en el repo**.

## 6. Página de cada equipo

Archivos: `ui/screens/sports/TeamDetailScreen.kt` y `TeamDetailViewModel.kt` (ruta `sports_team/{leagueId}/{teamId}`).
Es la misma página para todos los equipos de todas las ligas. Se abre al escoger un equipo en Sports
(Teams, My Teams), en la página de Teams, en la pestaña Teams de una liga y en la Library.

- **Header:** el logo del equipo; arriba en gris la ciudad o el estado ("Buffalo", "New Orleans",
  "Green Bay") y abajo en blanco el nombre ("Bills", "Saints", "Packers"), en NFL, NBA, MLB y NHL.
  En **soccer** el nombre completo va en blanco ("Real Madrid", "Barcelona"), sin línea gris.
- **Datos:** Team Record, Conference Rank y Division Rank (soccer: récord, posición en la liga y puntos).
- **Botón:** solo **Add to Library**. Pone el equipo en My Teams (equipos favoritos) y en la Library.
- **Foto del jugador** a la derecha: la estrella del equipo (el que más juegos lideró), con las fotos
  recortadas de ESPN (`a.espncdn.com/i/headshots/...`).
- **Pestañas:** Schedule, Roster, Stats, News, Videos, History.
- **Schedule:** abre en el juego (o la semana) de ahora. Hacia abajo, lo que viene; hacia arriba, lo
  que ya pasó. NFL por semanas ("Week 5", con las semanas de descanso); las demás ligas por día. Cada
  juego: colores de los dos equipos, logos, abreviaturas y marcador, Final / hora / canal, fotos de
  jugadores en las esquinas y Recap / Watch Live / Details. Abajo, todos los equipos de la liga.
- **Roster:** los jugadores con foto, número y posición. **Stats:** las estadísticas de la temporada.
  **News:** las noticias del equipo en ESPN. **History:** el resumen del equipo en Wikipedia y su estadio.
- **Videos:** pendiente; ahí irán los highlights y el Watch Replay (punto 5).

## 7. Orden de trabajo (paso a paso)

1. Este documento.
2. Página de cada equipo (hecha, punto 6).
3. Conectar los juegos al IPTV/EPG (punto 3).
4. Watch Live con la señal del IPTV.
5. Watch Replay con los highlights de YouTube (punto 5).

Cada paso se entrega y se prueba con el **PR Full Debug Build** antes del siguiente.

## 8. Reglas del proyecto

- Las claves (TMDB y cualquier otra) viven **solo** en el secreto de GitHub `LOCAL_PROPERTIES_BASE64`.
  Nunca en el código ni en el repo.
- No se incluye la fuente SF Pro en la app.
- Los textos nuevos van en inglés (`values`) y en español (`values-es` y `values-b+es+419`).
- El estilo es el de Apple TV: los tokens de `AppleTv*` (colores, espacios, tipografía y tarjetas).
