package snd.komf.mediaserver.kavita

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KavitaApplyModeWriteGuardTest {

    @Test
    fun `CORE forbids chapter update writes`() {
        assertFalse(isWriteEndpointAllowedForApplyMode("CORE", KavitaEndpoint.CHAPTER_UPDATE))
    }

    @Test
    fun `CORE allows expected core writes`() {
        assertTrue(isWriteEndpointAllowedForApplyMode("CORE", KavitaEndpoint.SERIES_UPDATE))
        assertTrue(isWriteEndpointAllowedForApplyMode("CORE", KavitaEndpoint.SERIES_METADATA_POST))
        assertTrue(isWriteEndpointAllowedForApplyMode("CORE", KavitaEndpoint.UPLOAD_SERIES))
        assertTrue(isWriteEndpointAllowedForApplyMode("CORE", KavitaEndpoint.UPLOAD_VOLUME))
    }

    @Test
    fun `CHAPTERS allows chapter updates and forbids core metadata writes`() {
        assertTrue(isWriteEndpointAllowedForApplyMode("CHAPTERS", KavitaEndpoint.CHAPTER_UPDATE))
        assertFalse(isWriteEndpointAllowedForApplyMode("CHAPTERS", KavitaEndpoint.SERIES_UPDATE))
        assertFalse(isWriteEndpointAllowedForApplyMode("CHAPTERS", KavitaEndpoint.SERIES_METADATA_POST))
        assertFalse(isWriteEndpointAllowedForApplyMode("CHAPTERS", KavitaEndpoint.UPLOAD_SERIES))
        assertFalse(isWriteEndpointAllowedForApplyMode("CHAPTERS", KavitaEndpoint.UPLOAD_VOLUME))
    }

    @Test
    fun `FULL allows chapter and core writes`() {
        assertTrue(isWriteEndpointAllowedForApplyMode("FULL", KavitaEndpoint.CHAPTER_UPDATE))
        assertTrue(isWriteEndpointAllowedForApplyMode("FULL", KavitaEndpoint.SERIES_UPDATE))
        assertTrue(isWriteEndpointAllowedForApplyMode("FULL", KavitaEndpoint.UPLOAD_VOLUME))
    }
}
