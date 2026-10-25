package io.maildots.app

import io.maildots.app.web.AccountService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountServiceTest {
    @Test
    fun everythingTalksToMaildotsCom() {
        assertEquals("https://webmail.maildots.com", AccountService.BASE_URL)
        assertEquals(AccountService.BASE_URL, AccountService.WEB_URL)
        assertEquals("webmail.maildots.com", AccountService.WEB_HOST)
    }

    @Test
    fun imageProxyIsOnlyTheService() {
        assertEquals(setOf(AccountService.BASE_URL), AccountService.IMAGE_BASES)
        assertTrue(AccountService.IMAGE_BASES.none { it.contains("maildots.io") })
    }
}
