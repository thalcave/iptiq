package main.java;

import com.sun.istack.internal.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class which handles multiple processes.
 *
 * Has the following functionality:
 * <li> add a process
 * <li> list running processes (sorting them by different criteria)
 * <li> kill processes
 *
 * The task manager has a maximum capacity which limits the number of running processes.
 * If a new process is added while the limit is reached, a specific strategy (selected at build time)
 * will be applied:
 * <li> the new process is rejected
 * <li> the oldest process is killed and removed, and the new process is added
 * <li> the oldest and lowest process with lower priority than the new process is killed and removed, and the new process is added
 *
 */
public final class TaskManager
{
    private static final Logger logger = LogManager.getLogger();

    /**
     * Defines the behavior in case the limit of running processes is reached
     */
    public enum SizeExceededStrategy
    {
        rejectNew,  // the new process is rejected
        killOldest, // oldest process is killed and removed
        killLowest  // lowest and oldest process is killed and removed
    }

    private final int maxSize;

    // currently running processes, ordered by insertion
    private final Map<Integer, Process> runningProcesses;
    // currently running processes, separated by priority, and maintaining the insertion order
    private final Map<Process.Priority, LinkedHashSet<Integer>> processesByPriority= new HashMap<>();
    // object which adds a process to currently running processes
    private final ProcessAdder processAdder;

    public TaskManager(final int maxSize)
    {
        this(maxSize, SizeExceededStrategy.rejectNew);
    }

    public TaskManager(final int maxSize, final SizeExceededStrategy strategy)
    {
        if (maxSize <= 0)
        {
            throw new IllegalArgumentException("maxSize must be a positive integer");
        }
        this.maxSize = maxSize;

        // build empty structures
        runningProcesses = new LinkedHashMap<>(maxSize);
        Stream
                .of(Process.Priority.values())
                .forEach(priority -> processesByPriority.put(priority, new LinkedHashSet<>()));

        processAdder = buildProcessAdder(strategy);
    }

    /**
     * Add a process to the list of running ones.
     * @return if the process was added or not.
     */
    public boolean add(final Process p)
    {
        return processAdder.add(p);
    }

    public enum SortingCriterion
    {
        creationDate,
        priority,
        pid
    }

    /**
     * List running processes, sorted by different criteria.
     */
    public void list(final SortingCriterion sortBy)
    {
        final Collection<Process> processesToList = getProcessesToList(sortBy);
        logger.debug("List running processes sorted by: {};\n{}", sortBy, processesToList);
    }

    /**
     * Kill the process identified by pid.
     * @return if the process was successfully killed.
     */
    public boolean kill(final int pid)
    {
        final Process process = runningProcesses.get(pid);
        if (process == null)
        {
            logger.debug("Process is not running: {}", pid);
            return false;
        }

        return killAndRemove(process);
    }

    /**
     * Kill all processes with this priority.
     * @return number of killed processes.
     */
    public int killGroup(final Process.Priority priority)
    {
        final Set<Integer> pidsToBeKilled = processesByPriority.get(priority);
        final int numberOfKilledProcesses = pidsToBeKilled.size();
        pidsToBeKilled.forEach(runningProcesses::remove);
        pidsToBeKilled.clear();

        logger.debug("Killed {} processes with priority: {}", numberOfKilledProcesses, priority);
        return numberOfKilledProcesses;
    }

    /**
     * Kill all running processes.
     * @return number of killed processes.
     */
    public int killAll()
    {
        final Collection<Process> processesToBeKilled = runningProcesses.values();
        final int numOfKilledProcesses = processesToBeKilled.size();
        processesToBeKilled
                .forEach(Process::kill);

        runningProcesses.clear();
        Stream
                .of(Process.Priority.values())
                .map(processesByPriority::get)
                .forEach(LinkedHashSet::clear);

        logger.debug("Kill {} processes", numOfKilledProcesses);
        return numOfKilledProcesses;
    }

    public int getNumberOfRunningProcesses()
    {
        return runningProcesses.size();
    }

    public boolean isRunning(final int pid)
    {
        return runningProcesses.containsKey(pid);
    }

    private ProcessAdder buildProcessAdder(final SizeExceededStrategy strategy)
    {
        switch (strategy) {
            case rejectNew:
                return new ProcessAdderDefault();
            case killOldest:
                return new ProcessAdderFifo();
            case killLowest:
                return new ProcessAdderPriority();
            default:
                throw new RuntimeException("Invalid strategy: " + strategy);
        }
    }

    private abstract static class ProcessAdder
    {
        abstract boolean add(final Process newProcess);
    }

    /**
     * Adds a new process to the list of running processes.
     *
     * If the list is full (max. size is reached), discard the new process.
     */
    private final class ProcessAdderDefault extends ProcessAdder
    {
        @Override
        boolean add(final Process newProcess)
        {
            if (runningProcesses.size() < maxSize)
            {
                return addProcess(newProcess);
            }

            logger.debug("Running processes limit reached, will discard process: {}", newProcess);
            return false;
        }
    }

    /**
     * Adds a new process to the list of running processes.
     *
     * If the list is full (max. size is reached), discard the oldest running process,
     * and add the new process.
     */
    private final class ProcessAdderFifo extends ProcessAdder
    {
        @Override
        boolean add(final Process newProcess)
        {
            if (runningProcesses.size() < maxSize) {
                return addProcess(newProcess);
            }

            final Process oldestProcess = runningProcesses.values().iterator().next();
            return killAndRemove(oldestProcess) && addProcess(newProcess);
        }
    }

    /**
     * Adds a new process to the list of running processes.
     *
     * If the list is full (max. size is reached),
     * search for the oldest process with lower priority than the new one,
     * and if it exists, discard it and add the new process.
     */
    private final class ProcessAdderPriority extends ProcessAdder
    {
        @Override
        boolean add(final Process newProcess)
        {
            if (runningProcesses.size() < maxSize) {
                return addProcess(newProcess);
            }

            final Process candidateForReplacement = findCandidateForReplacement(newProcess);
            if (candidateForReplacement == null)
            {
                logger.debug("Running processes limit reached; no candidate found for killing; will discard {}", newProcess);
                return false;
            }

            return killAndRemove(candidateForReplacement) && addProcess(newProcess);
        }

        /**
         * From the pool of processes with lower priority than the newProcess,
         * return the one with lowest priority and oldest creation date.
         *
         * @return null if there are no processes with lower priority.
         */
        @Nullable
        private Process findCandidateForReplacement(final Process newProcess)
        {

            final Process.Priority[] priorityValues = Process.Priority.values();
            for (final Process.Priority priorityValue : priorityValues)
            {
                if (priorityValue.getNumVal() >= newProcess.getPriority().getNumVal()) {
                    logger.debug("Cannot find processes lower than: {}; will discard it", newProcess);
                    return null;
                }

                // check if there are running processes with this priority, which is lower than newProcess' priority
                final LinkedHashSet<Integer> pidsWithPriority = processesByPriority.get(priorityValue);
                if (pidsWithPriority != null && !pidsWithPriority.isEmpty())
                {
                    final Integer candidatePid = pidsWithPriority.iterator().next();   // get the first one, i.e. the oldest one
                    final Process candidateProcess = runningProcesses.get(candidatePid);

                    assert candidateProcess != null;
                    return candidateProcess;
                }
            }

            return null;
        }
    }

    /**
     * Add the new process in the map of running processes,
     * and in the priority map.
     *
     * @return whether the new process was added or not
     */
    private boolean addProcess(final Process newProcess)
    {
        assert runningProcesses.size() < maxSize;
        final Process existingProcess = runningProcesses.putIfAbsent(newProcess.getPid(), newProcess);
        if (existingProcess != null)
        {
            logger.info("{} is already running", newProcess);
            return false;
        }

        final LinkedHashSet<Integer> pidsWithPriority = processesByPriority.get(newProcess.getPriority());
        assert pidsWithPriority != null;
        pidsWithPriority.add(newProcess.getPid());
        logger.debug("Added {}", newProcess);

        return true;
    }

    /**
     * Kill the process, and remove it from the map of running processes,
     * and from the priority map.
     *
     * @return whether the new process was killed and removed or not.
     */
    private boolean killAndRemove(final Process process)
    {
        process.kill();
        runningProcesses.remove(process.getPid());

        // remove it from the priority map
        final LinkedHashSet<Integer> pids = processesByPriority.get(process.getPriority());
        assert pids != null;

        boolean wasRemoved = pids.remove(process.getPid());
        if (!wasRemoved)
        {
            assert false : "Could not find " + process + " in priority map";
            return false;
        }

        logger.debug("Killed and removed {}", process);
        return true;
    }

    private Collection<Process> getProcessesToList(final SortingCriterion sortBy)
    {
        switch (sortBy) {
            case creationDate:
                return runningProcesses.values();
            case priority:
                return Stream
                        .of(Process.Priority.values())
                        .map(processesByPriority::get)
                        .collect(Collectors.toList())
                        .stream()
                        .flatMap(Set::stream)
                        .map(runningProcesses::get)
                        .collect(Collectors.toList());
            case pid:
                return runningProcesses
                        .keySet()
                        .stream()
                        .sorted()
                        .map(runningProcesses::get)
                        .collect(Collectors.toList());
            default:
                throw new RuntimeException("Invalid sorting criterion: " + sortBy);
        }
    }
}
