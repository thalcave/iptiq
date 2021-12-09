package main.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A process is identified by 2 fields:
 * <li> a unique unmodifiable identifier (PID)
 * <li> a priority (low, medium, high)
 */
public final class Process
{
    private static final Logger logger = LogManager.getLogger();

    public enum Priority
    {
        LOW(0),
        MEDIUM(1),
        HIGH(2);

        private final int numVal;

        Priority(int numVal)
        {
            this.numVal = numVal;
        }

        public int getNumVal()
        {
            return numVal;
        }
    }

    private final int pid;
    private final Priority priority;

    public Process(final int pid, final Priority priority) {
        this.pid = pid;
        this.priority = priority;
    }

    public int getPid()
    {
        return pid;
    }

    public Priority getPriority()
    {
        return priority;
    }

    public void kill()
    {
        logger.debug("Terminated {}", pid);
    }

    @Override
    public String toString()
    {
        return "Process{pid=" + pid + ", priority=" + priority + '}';
    }
}
