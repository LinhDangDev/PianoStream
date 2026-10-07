package com.pianostream.core.youtube.potoken

/**
 * Token binding domain (F3). GVS (googlevideo session) tokens are bound to a
 * video; visitor tokens are bound to `visitorData`. The two never cross-use:
 * separate in-memory caches, a GVS token is never sent on the player request.
 */
internal enum class PoTokenBinding { VISITOR, GVS }
