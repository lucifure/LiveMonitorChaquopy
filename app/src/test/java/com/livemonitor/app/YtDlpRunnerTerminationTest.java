package com.livemonitor.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

public class YtDlpRunnerTerminationTest {
    @Test
    public void productionTerminatorConfirmsExitOfRealChildProcess() throws Exception {
        String javaName = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? "java.exe"
            : "java";
        File java = new File(new File(System.getProperty("java.home"), "bin"), javaName);
        Assume.assumeTrue("Host Java executable is unavailable.", java.isFile());
        String testClasses = new File(
            Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).getAbsolutePath();

        Process child = new ProcessBuilder(
            java.getAbsolutePath(),
            "-cp",
            testClasses,
            Sleeper.class.getName()
        ).start();
        try {
            assertTrue(YtDlpRunner.terminateProcess(child, 50L, 3_000L));
            assertFalse(child.isAlive());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void productionTerminatorReportsUnconfirmedExitWhenProcessIgnoresKills() {
        NonTerminatingProcess process = new NonTerminatingProcess();

        assertFalse(YtDlpRunner.terminateProcess(process, 1L, 1L));
        assertTrue(process.forcibleTerminationRequested);
        assertTrue(process.alive);
    }

    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(TimeUnit.MINUTES.toMillis(2));
        }
    }

    private static final class NonTerminatingProcess extends Process {
        private boolean alive = true;
        private boolean forcibleTerminationRequested;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("still running");
            return 0;
        }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() {
            forcibleTerminationRequested = true;
            return this;
        }
    }
}
