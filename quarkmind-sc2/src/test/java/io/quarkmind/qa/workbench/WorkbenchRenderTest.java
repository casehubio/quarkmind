package io.quarkmind.qa.workbench;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Tag("browser")
class WorkbenchRenderTest {

    @Inject Event<PatternAssessmentPublished> patternEvent;
    @Inject WorkbenchBroadcaster broadcaster;
    @Inject
            io.quarkmind.agent.AgentOrchestrator orchestrator;
    @Inject
            io.quarkmind.sc2.mock.SimulatedGame simulatedGame;
    @Inject
            io.quarkmind.sc2.ScenarioRunner scenarioRunner;


    @TestHTTPResource("/visualizer.html")
    URI visualizerUri;

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
        page.navigate(visualizerUri.toString());
        page.waitForFunction("() => window.__test && window.__test.threeReady() && window.__test.workbenchReady()");
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    @Test
    void shell_renders_with_blocks_ui_layout() {
        assertNotNull(page.querySelector("blocks-split-workbench"));
        assertNotNull(page.querySelector("blocks-detail-pane"));
        assertNotNull(page.querySelector("#wb-canvas"));
        assertNotNull(page.querySelector("#wb-status"));
        assertEquals("pattern", page.evaluate("() => window.__test.workbenchPage()"));
    }

    @Test
    void tab_switching_shows_correct_page() {
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-coaching\"]').click()");
        assertEquals("coaching", page.evaluate("() => window.__test.workbenchPage()"));
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-strategy\"]').click()");
        assertEquals("strategy", page.evaluate("() => window.__test.workbenchPage()"));
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-pattern\"]').click()");
        assertEquals("pattern", page.evaluate("() => window.__test.workbenchPage()"));
    }

    @Test
    void pattern_event_populates_page() throws Exception {
        broadcaster.waitForSession(5000);
        patternEvent.fire(new PatternAssessmentPublished(
            List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings", AssessmentSource.DROOLS))));
        page.waitForFunction("() => window.__test.workbenchPatternCount() > 0", null,
            new Page.WaitForFunctionOptions().setTimeout(5000));
        int count = ((Number) page.evaluate("() => window.__test.workbenchPatternCount()")).intValue();
        assertTrue(count >= 1);
    }

    @Test
    void full_pipeline_populates_pattern_tab_with_screenshot() throws Exception {
        simulatedGame.reset();
        orchestrator.startGame();
        broadcaster.waitForSession(5000);

        scenarioRunner.run("spawn-enemy-attack");
        for (int i = 0; i < 3; i++) {orchestrator.gameTick();}

        page.waitForFunction("() => window.__test.workbenchPatternCount() > 0", null,
                             new Page.WaitForFunctionOptions().setTimeout(5000));

        String patternText = (String) page.evaluate(
                "() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('qm-pattern-page').shadowRoot.textContent");
        org.assertj.core.api.Assertions.assertThat(patternText).as("Pattern tab should have data").doesNotContain("No pattern data");
        org.assertj.core.api.Assertions.assertThat(patternText).as("Pattern tab should show confidence").contains("%");

        page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/workbench-pattern.png")).setFullPage(true));

        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-strategy\"]').click()");
        Thread.sleep(200);
        page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/workbench-strategy.png")).setFullPage(true));

        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-coaching\"]').click()");
        Thread.sleep(200);
        page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/workbench-coaching.png")).setFullPage(true));
    }

    @Test
    void strategy_tab_shows_data_after_pipeline() throws Exception {
        simulatedGame.reset();
        orchestrator.startGame();
        broadcaster.waitForSession(5000);

        scenarioRunner.run("spawn-enemy-attack");
        for (int i = 0; i < 3; i++) {orchestrator.gameTick();}

        // Strategy event has fired — workbenchState.strategy is set.
        // Click strategy tab — element is created lazily.
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-strategy\"]').click()");

        page.waitForFunction(
                "() => { var dp = document.querySelector('blocks-detail-pane'); if (!dp || !dp.shadowRoot) return false;" +
                " var sp = dp.shadowRoot.querySelector('qm-strategy-page'); if (!sp || !sp.shadowRoot) return false;" +
                " return sp.shadowRoot.textContent.indexOf('Active Strategy') !== -1; }",
                null, new Page.WaitForFunctionOptions().setTimeout(5000));

        String text = (String) page.evaluate(
                "() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('qm-strategy-page').shadowRoot.textContent");
        org.assertj.core.api.Assertions.assertThat(text).contains("Active Strategy");
    }

    @Test
    void coaching_tab_shows_mode_info_without_llm_events() throws Exception {
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-coaching\"]').click()");
        Thread.sleep(300);

        String text = (String) page.evaluate(
                "() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('qm-coaching-page').shadowRoot.textContent");
        org.assertj.core.api.Assertions.assertThat(text).as("Coaching tab should show status text").isNotBlank();
    }

    @Test
    void commentary_tab_shows_status_text() throws Exception {
        page.evaluate("() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls=\"panel-commentary\"]').click()");
        Thread.sleep(300);

        String text = (String) page.evaluate(
                "() => document.querySelector('blocks-detail-pane').shadowRoot.querySelector('qm-commentary-page').shadowRoot.textContent");
        org.assertj.core.api.Assertions.assertThat(text).as("Commentary tab should show status text").isNotBlank();
    }


    @Test
    void empty_canvas_click_clears_selection() {
        page.click("#wb-canvas canvas", new Page.ClickOptions().setPosition(10, 10));
        int rings = ((Number) page.evaluate("() => window.__test.selectionRingCount()")).intValue();
        assertEquals(0, rings);
    }
}
