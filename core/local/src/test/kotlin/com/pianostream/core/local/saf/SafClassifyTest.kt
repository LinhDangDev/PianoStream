package com.pianostream.core.local.saf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `classify` MIME/ext agreement — the AUDIO kind covers the loose-audio
 * extension set with loose agreement (an `audio/` MIME prefix,
 * application/octet-stream, or empty); any disagreement still lands on
 * OTHER. `.flac` keeps its own kind; cue/log/jpeg/pruned/folder behavior
 * is unchanged.
 */
class SafClassifyTest {

    @Test
    fun audio_extensions_classify_audio() {
        for (ext in listOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "wma")) {
            assertThat(ContentResolverSafSource.classify("track.$ext", "audio/mpeg"))
                .isEqualTo(SafDocumentKind.AUDIO)
        }
    }

    @Test
    fun audio_exts_accept_octet_stream_and_empty_mime() {
        assertThat(ContentResolverSafSource.classify("a.MP3", "application/octet-stream"))
            .isEqualTo(SafDocumentKind.AUDIO)
        assertThat(ContentResolverSafSource.classify("a.m4a", ""))
            .isEqualTo(SafDocumentKind.AUDIO)
        assertThat(ContentResolverSafSource.classify("a.WAV", "AUDIO/X-WAV"))
            .isEqualTo(SafDocumentKind.AUDIO)
    }

    @Test
    fun audio_ext_disagreeing_mime_is_other() {
        assertThat(ContentResolverSafSource.classify("a.mp3", "text/plain"))
            .isEqualTo(SafDocumentKind.OTHER)
        assertThat(ContentResolverSafSource.classify("a.opus", "image/png"))
            .isEqualTo(SafDocumentKind.OTHER)
        assertThat(ContentResolverSafSource.classify("a.wma", "video/mp4"))
            .isEqualTo(SafDocumentKind.OTHER)
    }

    @Test
    fun flac_keeps_dedicated_kind() {
        assertThat(ContentResolverSafSource.classify("cd.flac", "audio/flac"))
            .isEqualTo(SafDocumentKind.FLAC)
        assertThat(ContentResolverSafSource.classify("cd.flac", "audio/x-flac"))
            .isEqualTo(SafDocumentKind.FLAC)
        assertThat(ContentResolverSafSource.classify("cd.flac", "text/plain"))
            .isEqualTo(SafDocumentKind.OTHER)
    }

    @Test
    fun existing_kinds_unchanged() {
        assertThat(ContentResolverSafSource.classify("a.cue", "text/plain"))
            .isEqualTo(SafDocumentKind.CUE)
        assertThat(ContentResolverSafSource.classify("a.cue", "application/octet-stream"))
            .isEqualTo(SafDocumentKind.CUE)
        assertThat(ContentResolverSafSource.classify("a.log", "text/plain"))
            .isEqualTo(SafDocumentKind.LOG)
        assertThat(ContentResolverSafSource.classify("Front.JPEG", "image/jpeg"))
            .isEqualTo(SafDocumentKind.JPEG)
        assertThat(ContentResolverSafSource.classify("score.pdf", "application/pdf"))
            .isEqualTo(SafDocumentKind.PRUNED)
        assertThat(ContentResolverSafSource.classify("x.bin", "application/octet-stream"))
            .isEqualTo(SafDocumentKind.OTHER)
        assertThat(
            ContentResolverSafSource.classify("dir", "vnd.android.document/directory"),
        ).isEqualTo(SafDocumentKind.FOLDER)
    }
}
