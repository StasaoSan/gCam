package com.stasao.gcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GCamReleaseAssetNamesTest {
    @Test fun selectsCanonicalApkFromReleaseAssets() {
        assertEquals("gCam-v5.5.apk", GCamReleaseAssetNames.find("v5.5",
            listOf("release-notes.txt", "gCam-v5.5.apk.sha256", "gCam-v5.5.apk")))
    }

    @Test fun acceptsActualNormalizedLegacyReleaseName() {
        assertEquals("gCam_.v5.4.apk", GCamReleaseAssetNames.find("v5.4", listOf("gCam_.v5.4.apk")))
        assertEquals("gCam_[v5.4].apk", GCamReleaseAssetNames.find("v5.4", listOf("gCam_[v5.4].apk")))
    }

    @Test fun canonicalApkWinsWhenLegacyAssetIsListedFirst() {
        assertEquals("gCam-v5.5.apk", GCamReleaseAssetNames.find("v5.5",
            listOf("gCam_.v5.5.apk", "gCam_[v5.5].apk", "gCam-v5.5.apk")))
    }

    @Test fun wrongVersionsAndMalformedNamesAreRejected() {
        for (name in listOf("gCam-v5.4.apk", "app.apk", "gCam-v5.5.APK", "gCam-v5.5.apk.part",
            "../gCam-v5.5.apk", "folder/gCam-v5.5.apk", "gCam-v5.5.apk?download=1",
            "gCam-v5.5.apk#file", "gCam-v5.5.apk ", "gCam-v5.5%2F.apk")) {
            assertNull(name, GCamReleaseAssetNames.find("v5.5", listOf(name)))
        }
    }

    @Test fun invalidTagCannotConstructAnAcceptedAssetPath() {
        for (tag in listOf("5.5", "v5", "v5.5/../file", "v5.5?x", "v5.5#x", "v5.5\n", "v5.5.0.1.2")) {
            assertNull(tag, GCamReleaseAssetNames.find(tag, listOf("gCam-$tag.apk")))
        }
    }
}
