package test.java;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import main.java.TaskManager;
import main.java.Process;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.Collections;
import java.util.Random;
import java.util.stream.Stream;

final class TaskManagerTest
{
    private static final Logger logger = LogManager.getLogger();

    static int currentPid = 1;
    private int getUniquePid()
    {
        return currentPid++;
    }

    final Random random = new Random();
    private Process.Priority getRandomPriority()
    {
        int index = random.nextInt(Process.Priority.values().length);
        return Process.Priority.values()[index];
    }

    private Process buildRandomProcess()
    {
        return new Process(getUniquePid(), getRandomPriority());
    }

    @BeforeEach
    void setUp(TestInfo testInfo)
    {
        logger.info("Test started: {}", testInfo.getDisplayName());
    }

    @AfterEach
    void tearDown(TestInfo testInfo)
    {
        logger.info("Test finished: {}", testInfo.getDisplayName());
    }

    @Test
    void testAddSingleProcess()
    {
        Stream
                .of(TaskManager.SizeExceededStrategy.values())
                .forEach(this::doTestAddSingleProcess);
    }

    @Test
    void testAddSingleProcessDuplicate()
    {
        Stream
                .of(TaskManager.SizeExceededStrategy.values())
                .forEach(this::doTestAddSingleProcessDuplicate);
    }

    @Test
    void testAddProcessesExceedingLimitRejectNewStrategy()
    {
        int maxSize = 10;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.rejectNew);

        for (int i = 0; i < maxSize; ++i)
        {
            assertTrue(taskManager.add(buildRandomProcess()));
            assertEquals(i+1, taskManager.getNumberOfRunningProcesses());
        }

        for (int i = 0; i < 5; ++i)
        {
            assertFalse(taskManager.add(buildRandomProcess()));
            assertEquals(maxSize, taskManager.getNumberOfRunningProcesses());
        }
    }

    @Test
    void testAddProcessesExceedingLimitKillOldestStrategy()
    {
        int maxSize = 10;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.killOldest);

        for (int i = 0; i < maxSize; ++i)
        {
            assertTrue(taskManager.add(buildRandomProcess()));
            assertEquals(i+1, taskManager.getNumberOfRunningProcesses());
        }

        for (int i = 0; i < 5; ++i)
        {
            assertTrue(taskManager.add(buildRandomProcess()));
            assertEquals(maxSize, taskManager.getNumberOfRunningProcesses());
        }
    }

    /**
     * Add only HIGH priority processes until the limit is reached.
     *
     * Try to add other processes -> it should fail.
     */
    @Test
    void testAddProcessesExceedingLimitWithKillLowestStrategy1()
    {
        int maxSize = 3;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.killLowest);

        for (int i = 0; i < maxSize; ++i)
        {
            assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
            assertEquals(i+1, taskManager.getNumberOfRunningProcesses());
        }

        Stream
                .of(Process.Priority.values())
                .forEach(priority -> assertFalse(taskManager.add(new Process(getUniquePid(), priority))));
    }

    /**
     * Add HIGH and MEDIUM priority processes until the limit is reached.
     *
     * Add a MEDIUM process -> it should fail.
     * Add a HIGH process -> it should pass.
     */
    @Test
    void testAddProcessesExceedingLimitWithKillLowestStrategy2()
    {
        int maxSize = 4;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.killLowest);

        for (int i = 0; i < maxSize/2; ++i)
        {
            assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
            assertEquals(i+1, taskManager.getNumberOfRunningProcesses());
        }
        for (int i = maxSize/2; i < maxSize; ++i)
        {
            assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.MEDIUM)));
            assertEquals(i+1, taskManager.getNumberOfRunningProcesses());
        }

        assertFalse(taskManager.add(new Process(getUniquePid(), Process.Priority.MEDIUM)));
        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
    }

    /**
     * Add 2 HIGH, 2 MEDIUM, and 2 LOW priority process with limit = 6.
     *
     * Add a LOW process -> it should fail.
     * Add a MEDIUM process -> it should pass, and oldest LOW process is removed
     * Add a HIGH process -> it should pass, and second oldest LOW process is removed
     *
     * Add a MEDIUM process -> it should fail (there are no more low priority processes).
     * Add a HIGH process -> it should pass, and oldest MEDIUM process is removed
     * Add a HIGH process -> it should pass, and second oldest MEDIUM process is removed
     * Add a HIGH process -> it should pass, and third oldest MEDIUM process is removed
     *
     * Add a HIGH process -> it should fail (there are no more processes with lower priority).
     */
    @Test
    void testAddProcessesExceedingLimitWithKillLowestStrategy3()
    {
        int maxSize = 6;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.killLowest);

        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));

        final Process oldestMediumProcess = new Process(getUniquePid(), Process.Priority.MEDIUM);
        assertTrue(taskManager.add(oldestMediumProcess));
        final Process secondOldestMediumProcess = new Process(getUniquePid(), Process.Priority.MEDIUM);
        assertTrue(taskManager.add(secondOldestMediumProcess));

        final Process oldestLowProcess = new Process(getUniquePid(), Process.Priority.LOW);
        assertTrue(taskManager.add(oldestLowProcess));
        final Process secondOldestLowProcess = new Process(getUniquePid(), Process.Priority.LOW);
        assertTrue(taskManager.add(secondOldestLowProcess));

        // fails because there are no processes with lower priority
        assertFalse(taskManager.add(new Process(getUniquePid(), Process.Priority.LOW)));

        final Process lastMediumProcess = new Process(getUniquePid(), Process.Priority.MEDIUM);
        assertTrue(taskManager.add(lastMediumProcess));
        assertFalse(taskManager.isRunning(oldestLowProcess.getPid()));
        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
        assertFalse(taskManager.isRunning(secondOldestLowProcess.getPid()));

        // fails because there are no processes with lower priority
        assertFalse(taskManager.add(new Process(getUniquePid(), Process.Priority.MEDIUM)));

        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
        assertFalse(taskManager.isRunning(oldestMediumProcess.getPid()));
        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
        assertFalse(taskManager.isRunning(secondOldestMediumProcess.getPid()));
        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
        assertFalse(taskManager.isRunning(lastMediumProcess.getPid()));

        // fails because there are no processes with lower priority
        assertFalse(taskManager.add(new Process(getUniquePid(), Process.Priority.HIGH)));
    }

    /**
     * Add 3 processes with same priority, with limit = 3.
     *
     * Add process with same priority -> it should fail.
     */
    @Test
    void testAddProcessesExceedingLimitWithKillLowestStrategy4()
    {
        Stream
                .of(Process.Priority.values())
                .forEach(this::doTestAddProcessesWithSamePriorityWithKillLowestStrategy);
    }

    @Test
    void testKillProcessesWithNoRunningProcesses()
    {
        final TaskManager taskManager = new TaskManager(10, TaskManager.SizeExceededStrategy.rejectNew);

        assertFalse(taskManager.kill(100));

        Stream
                .of(Process.Priority.values())
                .forEach(priority -> assertEquals(0, taskManager.killGroup(priority)));

        assertEquals(0, taskManager.killAll());
    }

    /**
     * Add a process to task manager.
     * Kill with a wrong pid.
     *
     * Expect no process was killed.
     */
    @Test
    void testKillProcessWithWrongPid()
    {
        final TaskManager taskManager = new TaskManager(10, TaskManager.SizeExceededStrategy.rejectNew);

        final Process process = buildRandomProcess();
        assertTrue(taskManager.add(process));

        assertEquals(1, taskManager.getNumberOfRunningProcesses());
        assertFalse(taskManager.kill(process.getPid() + 99));
        assertEquals(1, taskManager.getNumberOfRunningProcesses());
    }

    @Test
    void testKillNonExistentProcess()
    {
        TaskManager taskManager = new TaskManager(10, TaskManager.SizeExceededStrategy.rejectNew);
        assertFalse(taskManager.kill(getUniquePid()));
    }

    @Test
    void testKillSingleProcess()
    {
        Stream
                .of(TaskManager.SizeExceededStrategy.values())
                .forEach(this::doTestKillSingleProcess);
    }

    @Test
    void testKillProcessGroup()
    {
        Stream
                .of(TaskManager.SizeExceededStrategy.values())
                .forEach(this::doTestKillProcessGroup);
    }

    @Test
    void testKillNonExistentProcessGroup()
    {
        TaskManager taskManager = new TaskManager(10, TaskManager.SizeExceededStrategy.rejectNew);
        taskManager.add(new Process(getUniquePid(), Process.Priority.LOW));
        taskManager.add(new Process(getUniquePid(), Process.Priority.LOW));

        taskManager.add(new Process(getUniquePid(), Process.Priority.MEDIUM));
        taskManager.add(new Process(getUniquePid(), Process.Priority.MEDIUM));

        assertEquals(0, taskManager.killGroup(Process.Priority.HIGH));
    }


    @Test
    void testKillAllProcesses()
    {
        Stream
                .of(TaskManager.SizeExceededStrategy.values())
                .forEach(this::doTestKillAllProcesses);
    }

    @Test
    void testListByCreationDate()
    {
        doTestList(TaskManager.SortingCriterion.creationDate);
    }

    @Test
    void testListByPid()
    {
        doTestList(TaskManager.SortingCriterion.pid);
    }

    @Test
    void testListByPriority()
    {
        doTestList(TaskManager.SortingCriterion.priority);
    }

    /**
     * Creates a TaskManager with required strategy.
     * Adds 3 processes, kills one.
     * Expect the process is killed, and 2 are running.
     */
    private void doTestKillSingleProcess(final TaskManager.SizeExceededStrategy strategy)
    {
        logger.debug("Testing killing of single process with strategy: {}", strategy);

        final TaskManager taskManager = new TaskManager(10, strategy);

        assertTrue(taskManager.add(buildRandomProcess()));
        final Process toBeKilled = buildRandomProcess();
        assertTrue(taskManager.add(toBeKilled));
        assertTrue(taskManager.add(buildRandomProcess()));

        assertTrue(taskManager.kill(toBeKilled.getPid()));
        assertFalse(taskManager.isRunning(toBeKilled.getPid()));
        assertEquals(2, taskManager.getNumberOfRunningProcesses());
    }

    /**
     * Creates a TaskManager with required strategy.
     * For each priority, adds 3 processes (9 in total).
     *
     * Kill a process group.
     * Expect that 3 processes are killed.
     */
    private void doTestKillProcessGroup(final TaskManager.SizeExceededStrategy strategy)
    {
        logger.debug("Testing killing of process group with strategy: {}", strategy);

        final int maxSize = 3 * Process.Priority.values().length + 1;
        final TaskManager taskManager = new TaskManager(maxSize, strategy);

        Stream
                .of(Process.Priority.values())
                .forEach(priority ->
                        {
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                        }
                );

        for (int i = 0; i < Process.Priority.values().length; ++i)
        {
            final Process.Priority priority = Process.Priority.values()[i];
            logger.debug("Kill process group with priority: {}", priority);
            assertEquals(3, taskManager.killGroup(priority));

            final int numOfExpectedRunningProcesses = (Process.Priority.values().length - (i + 1)) * 3;
            assertEquals(numOfExpectedRunningProcesses, taskManager.getNumberOfRunningProcesses());
        }
    }

    /**
     * Creates a TaskManager with required strategy.
     * For each priority, adds 3 processes (9 in total).
     *
     * Kill all processes
     * Expect that 9 processes are killed.
     */
    private void doTestKillAllProcesses(final TaskManager.SizeExceededStrategy strategy)
    {
        logger.debug("Testing killing all processes with strategy: {}", strategy);

        final int numOfProcesses = 3 * Process.Priority.values().length;
        final TaskManager taskManager = new TaskManager(numOfProcesses + 1, strategy);

        Stream
                .of(Process.Priority.values())
                .forEach(priority ->
                        {
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
                        }
                );

        assertEquals(numOfProcesses, taskManager.getNumberOfRunningProcesses());
        assertEquals(numOfProcesses, taskManager.killAll());
        assertEquals(0, taskManager.getNumberOfRunningProcesses());
    }

    private void doTestAddSingleProcess(final TaskManager.SizeExceededStrategy strategy)
    {
        logger.debug("Testing adding single process with strategy: {}", strategy);
        final TaskManager taskManager = new TaskManager(10, strategy);

        final Process process = buildRandomProcess();
        assertTrue(taskManager.add(process));
        assertEquals(1, taskManager.getNumberOfRunningProcesses());
    }

    private void doTestAddSingleProcessDuplicate(final TaskManager.SizeExceededStrategy strategy)
    {
        logger.debug("Testing adding duplicated process with strategy: {}", strategy);
        final TaskManager taskManager = new TaskManager(10, strategy);

        final Process process = buildRandomProcess();
        assertTrue(taskManager.add(process));
        assertEquals(1, taskManager.getNumberOfRunningProcesses());

        assertFalse(taskManager.add(process));
    }

    /**
     * Creates a TaskManager.
     * For each priority, adds 3 processes (9 in total).
     *
     * List processes, order by sortingCriterion.
     */
    private void doTestList(final TaskManager.SortingCriterion sortingCriterion)
    {
        final int numOfProcesses = 3 * Process.Priority.values().length;
        final TaskManager taskManager = new TaskManager(numOfProcesses + 1, TaskManager.SizeExceededStrategy.rejectNew);

        assertTrue(taskManager.add(new Process(getUniquePid(), Process.Priority.LOW)));
        for (int i = 0; i < 3; ++i)
        {
            Stream
                    .of(Process.Priority.values())
                    .sorted(Collections.reverseOrder())
                    .forEach(priority -> assertTrue(taskManager.add(new Process(getUniquePid(), priority))));
        }

        taskManager.list(sortingCriterion);
    }

    private void doTestAddProcessesWithSamePriorityWithKillLowestStrategy(final Process.Priority priority)
    {
        int maxSize = 3;
        final TaskManager taskManager = new TaskManager(maxSize, TaskManager.SizeExceededStrategy.killLowest);

        for (int i = 0; i < maxSize; ++i)
        {
            assertTrue(taskManager.add(new Process(getUniquePid(), priority)));
        }

        assertFalse(taskManager.add(new Process(getUniquePid(), priority)));
    }
}
