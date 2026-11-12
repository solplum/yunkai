package com.wangzan101.mika.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerKindsTest {
    @Test fun specificMimeTypesDecide() {
        assertEquals(ViewerKind.Html, ViewerKinds.of("text/html", "page"))
        assertEquals(ViewerKind.Html, ViewerKinds.of("text/html; charset=utf-8", "x.bin"))
        assertEquals(ViewerKind.Markdown, ViewerKinds.of("text/markdown", "notes"))
        assertEquals(ViewerKind.Pdf, ViewerKinds.of("application/pdf", "report"))
        assertEquals(ViewerKind.Image, ViewerKinds.of("image/png", "shot"))
        assertEquals(ViewerKind.Image, ViewerKinds.of("image/jpeg", "photo"))
    }

    @Test fun genericMimeFallsBackToTheExtension() {
        assertEquals(ViewerKind.Markdown, ViewerKinds.of("text/plain", "README.md"))
        assertEquals(ViewerKind.Markdown, ViewerKinds.of(null, "a.MARKDOWN"))
        assertEquals(ViewerKind.Html, ViewerKinds.of("application/octet-stream", "index.htm"))
        assertEquals(ViewerKind.Pdf, ViewerKinds.of(null, "a.pdf"))
        assertEquals(ViewerKind.Image, ViewerKinds.of(null, "a.webp"))
        assertEquals(ViewerKind.Image, ViewerKinds.of("application/octet-stream", "a.JPG"))
        for (ext in listOf("txt", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts", "sh", "ini", "conf")) {
            assertEquals(ext, ViewerKind.Text, ViewerKinds.of("application/octet-stream", "file.$ext"))
        }
    }

    @Test fun textLikeMimeWithoutAKnownExtensionIsText() {
        assertEquals(ViewerKind.Text, ViewerKinds.of("text/plain", "LICENSE"))
        assertEquals(ViewerKind.Text, ViewerKinds.of("application/json", "data"))
        assertEquals(ViewerKind.Text, ViewerKinds.of("application/ld+json", "data"))
    }

    @Test fun everythingElseIsNotOpenedHere() {
        assertNull(ViewerKinds.of("application/zip", "a.zip"))
        assertNull(ViewerKinds.of("application/octet-stream", "a.apk"))
        assertNull(ViewerKinds.of(null, "noextension"))
        assertNull(ViewerKinds.of("video/mp4", "clip.mp4"))
        assertNull(ViewerKinds.of("image/svg+xml", "logo.svg"))
        assertNull(ViewerKinds.of(null, "slides.pptx"))
    }
}
