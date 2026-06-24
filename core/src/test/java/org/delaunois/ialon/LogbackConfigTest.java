package org.delaunois.ialon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards against logger-name drift in the production {@code logback.xml}. The chunk-paging classes live
 * in {@code org.delaunois.ialon.blocks}; an entry naming the old {@code org.delaunois.ialon.<Class>}
 * package silently does nothing (logback matches by dotted prefix), so the class falls back to the
 * {@code org.delaunois.ialon.blocks = INFO} level and its per-frame {@code log.info} calls fire on the
 * render thread — the cause of the movement stutter we fixed. This asserts the intended levels apply.
 */
class LogbackConfigTest {

    private static ch.qos.logback.classic.Logger loggerFrom(String resource, String name) throws Exception {
        LoggerContext ctx = new LoggerContext();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(ctx);
        try (InputStream is = LogbackConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(is, "missing " + resource + " on the classpath");
            jc.doConfigure(is);
        }
        return ctx.getLogger(name);
    }

    @Test
    void physicsChunkPagerIsSilentInProduction() throws Exception {
        ch.qos.logback.classic.Logger l =
                loggerFrom("/logback.xml", "org.delaunois.ialon.blocks.PhysicsChunkPager");
        // The per-frame paging logs must NOT reach the synchronous console appender during movement.
        assertEquals(Level.ERROR, l.getEffectiveLevel(),
                "PhysicsChunkPager should be ERROR — a stale logger name would leave it at INFO");
        assertFalse(l.isInfoEnabled(), "PhysicsChunkPager info logging must be off in production");
    }

    @Test
    void chunkManagerAndPagerLevelsApply() throws Exception {
        assertEquals(Level.ERROR,
                loggerFrom("/logback.xml", "org.delaunois.ialon.blocks.ChunkManager").getEffectiveLevel());
        assertEquals(Level.WARN,
                loggerFrom("/logback.xml", "org.delaunois.ialon.blocks.ChunkPager").getEffectiveLevel());
    }
}
