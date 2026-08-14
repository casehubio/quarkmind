package io.quarkmind.qa.workbench;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Tag("browser")
class WorkbenchRenderTest {

    @Inject Event<PatternAssessmentPublished> patternEvent;
    @Inject WorkbenchBroadcaster broadcaster;

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;

    @BeforeAll
    static void setup() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void teardown() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void newPage() {
        context = browser.newContext();
        page = context.newPage();
        page.navigate("http://localhost:8081/visualizer.html");
        page.waitForFunction("() => window.__test && window.__test.threeReady()");
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    @Test
    void shell_renders_with_toolbar_and_pages() {
        assertNotNull(page.querySelector("#wb-toolbar"));
        assertNotNull(page.querySelector("#wb-pages"));
        assertNotNull(page.querySelector("#wb-detail"));
        assertNotNull(page.querySelector("#wb-status"));
        assertEquals("pattern", page.evaluate("() => window.__test.workbenchPage()"));
    }

    @Test
    void tab_switching_shows_correct_page() {
        page.click("[data-page='coaching']");
        assertEquals("coaching", page.evaluate("() => window.__test.workbenchPage()"));
        page.click("[data-page='strategy']");
        assertEquals("strategy", page.evaluate("() => window.__test.workbenchPage()"));
        page.click("[data-page='pattern']");
        assertEquals("pattern", page.evaluate("() => window.__test.workbenchPage()"));
    }

    @Test
    void pattern_event_populates_page() throws Exception {
        broadcaster.waitForSession(5000);
        patternEvent.fire(new PatternAssessmentPublished(
            List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings"))));
        page.waitForFunction("() => window.__test.workbenchPatternCount() > 0", null,
            new Page.WaitForFunctionOptions().setTimeout(5000));
        int count = ((Number) page.evaluate("() => window.__test.workbenchPatternCount()")).intValue();
        assertTrue(count >= 1);
    }

    @Test
    void empty_canvas_click_clears_selection() {
        page.click("#wb-canvas canvas", new Page.ClickOptions().setPosition(10, 10));
        int rings = ((Number) page.evaluate("() => window.__test.selectionRingCount()")).intValue();
        assertEquals(0, rings);
    }
}
