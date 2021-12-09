# iptiq
Basic implementation of a task manager, written in Java 8.
The class TaskManager is not thread safe.


To enable logs when running tests from Intellij:
'Run/Debug Configurations' must be changed to include the full path to log4j.xml file.
Example:
  -ea -Dlog4j.configurationFile=/home/florin.micu/iptiq/src/test/resources/log4j.xml
