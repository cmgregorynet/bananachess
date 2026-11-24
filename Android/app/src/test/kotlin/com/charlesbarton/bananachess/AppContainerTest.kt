package com.charlesbarton.bananachess

import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.charlesbarton.bananachess.engine.cpu.CpuDifficulty
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.match.MatchSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val application: BananaChessApplication get() = ApplicationProvider.getApplicationContext()

    private val computerOnARandomBoard =
        MatchSource.New(
            kind = MatchSource.Kind.Computer(CpuDifficulty.Brutal, humanPlays = PlayerId.Two),
            ruleSet = RuleSet.Wildcard,
            boardSetup = BoardSetup.Random,
            boardWrap = BoardWrap.All,
            blenderBattles = true,
            seed = ULong.MAX_VALUE,
        )

    @Test
    fun `the application owns one container for the whole process`() {
        val container = application.container

        assertSame(container, application.container)
        assertIs<BananaChessApplication>(application)
    }

    @Test
    fun `recreating the activity keeps the container`() {
        val container = application.container
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(container, (activity.application as BananaChessApplication).container)
            }
        }
        assertSame(container, application.container)
    }

    @Test
    fun `a match source survives the saved back stack`() {
        for (source in listOf(computerOnARandomBoard, MatchSource.Saved)) {
            val bundle = Bundle()
            MatchSourceNavType.put(bundle, "source", source)
            assertEquals(source, MatchSourceNavType.get(bundle, "source"))
        }
    }

    @Test
    fun `a match source survives the route string`() {
        for (source in listOf(computerOnARandomBoard, MatchSource.Saved)) {
            val inRoute = MatchSourceNavType.serializeAsValue(source)
            assertEquals(source, MatchSourceNavType.parseValue(Uri.decode(inRoute)))
        }
    }

    @Test
    fun `the same source deals the same random board`() {
        assertEquals(computerOnARandomBoard.deal(), computerOnARandomBoard.copy().deal())
        assertEquals(BoardSetup.Random, computerOnARandomBoard.deal().state.boardSetup)
    }
}
