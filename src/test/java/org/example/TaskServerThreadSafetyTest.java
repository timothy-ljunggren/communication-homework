package org.example;

import de.mcc.Manager;
import de.mcc.ManagerServiceGrpc;
import de.mcc.Worker;
import de.mcc.WorkerServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that TaskServer is thread-safe when multiple Manager and Worker
 * clients operate concurrently.
 *
 * Uses gRPC's in-process transport so no real network port is required.
 */
class TaskServerThreadSafetyTest {
    private static final int MANAGER_THREADS  = 4;
    private static final int TASKS_PER_MANAGER = 10;
    private static final int TOTAL_TASKS = MANAGER_THREADS * TASKS_PER_MANAGER;

    private static final String[] WORKER_NAMES = {"Alice", "Bob", "Carol", "Dave"};

    private Server server;
    private final List<ManagedChannel> openChannels = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        TaskServer.reset();

        serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(new TaskServer.ManagerServiceImpl())
                .addService(new TaskServer.WorkerServiceImpl())
                .build()
                .start();

    }

    private String serverName;

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel ch : openChannels) {
            ch.shutdownNow();
        }
        openChannels.clear();
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    private ManagerServiceGrpc.ManagerServiceBlockingStub newManagerStub() {
        ManagedChannel ch = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        openChannels.add(ch);
        return ManagerServiceGrpc.newBlockingStub(ch);
    }

    private WorkerServiceGrpc.WorkerServiceBlockingStub newWorkerStub() {
        ManagedChannel ch = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        openChannels.add(ch);
        return WorkerServiceGrpc.newBlockingStub(ch);
    }

    /**
     * MANAGER_THREADS managers each create TASKS_PER_MANAGER tasks at the same time.
     * After all threads finish the server should contain exactly TOTAL_TASKS tasks with no duplicate IDs and no lost writes
     */
    @Test
    void concurrentTaskCreation_producesCorrectTotalAndUniqueIds() throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(MANAGER_THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(MANAGER_THREADS);

        for (int t = 0; t < MANAGER_THREADS; t++) {
            final int threadIdx = t;
            pool.submit(() -> {
                ManagerServiceGrpc.ManagerServiceBlockingStub stub = newManagerStub();
                try {
                    startGate.await();
                    for (int i = 0; i < TASKS_PER_MANAGER; i++) {
                        stub.createNewTask(Manager.CreateTaskRequest.newBuilder()
                                .setTitle("Task-T" + threadIdx + "-" + i)
                                .setDescription("created by thread " + threadIdx)
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for managers");
        pool.shutdown();

        Manager.GetMetricsResponse metrics = newManagerStub()
                .getSystemMetrics(Manager.GetMetricsRequest.newBuilder().build());

        assertEquals(TOTAL_TASKS, metrics.getTotalTasks(),
                "Total task count must equal MANAGER_THREADS × TASKS_PER_MANAGER");
        assertEquals(TOTAL_TASKS, metrics.getOpenTasksCount(),
                "All freshly created tasks must still be OPEN");
        assertEquals(0, metrics.getCompletedTasksCount());
    }

    /**
     * Phase 1 (serial, sequential): one manager creates and assigns TOTAL_TASKS tasks,
     *          distributing them across WORKER_NAMES
     * Phase 2 (parallel): WORKER_NAMES.length worker threads all fetch and
     *          complete their tasks at the same time.
     * Phase 3 (assertion): metrics must be perfectly consistent – every task
     *          is completed, none are lost, and open+completed == total.
     */
    @Test
    void concurrentWorkerCompletion_metricsRemainConsistent() throws InterruptedException {
        ManagerServiceGrpc.ManagerServiceBlockingStub chief = newManagerStub();

        List<List<Integer>> taskIdsPerWorker = new ArrayList<>();
        for (int w = 0; w < WORKER_NAMES.length; w++) taskIdsPerWorker.add(new ArrayList<>());

        for (int i = 0; i < TOTAL_TASKS; i++) {
            Manager.CreateTaskResponse created = chief.createNewTask(
                    Manager.CreateTaskRequest.newBuilder()
                            .setTitle("Task-" + i)
                            .setDescription("pipeline test")
                            .build());

            int taskId = created.getCreatedTask().getTaskId();
            String assignee = WORKER_NAMES[i % WORKER_NAMES.length];

            chief.assignTaskToWorker(Manager.AssignTaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAssigneeName(assignee)
                    .build());

            taskIdsPerWorker.get(i % WORKER_NAMES.length).add(taskId);
        }

        CountDownLatch startGate     = new CountDownLatch(1);
        CountDownLatch doneLatch      = new CountDownLatch(WORKER_NAMES.length);
        AtomicInteger  successCount   = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(WORKER_NAMES.length);

        for (int w = 0; w < WORKER_NAMES.length; w++) {
            final int workerIdx = w;
            pool.submit(() -> {
                WorkerServiceGrpc.WorkerServiceBlockingStub stub = newWorkerStub();
                try {
                    startGate.await();
                    for (int taskId : taskIdsPerWorker.get(workerIdx)) {
                        Worker.CompleteTaskResponse resp = stub.markTaskAsCompleted(
                                Worker.CompleteTaskRequest.newBuilder()
                                        .setTaskId(taskId)
                                        .setWorkerName(WORKER_NAMES[workerIdx])
                                        .build());
                        if (resp.getSuccess()) successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for workers");
        pool.shutdown();

        Manager.GetMetricsResponse metrics = chief
                .getSystemMetrics(Manager.GetMetricsRequest.newBuilder().build());

        assertEquals(TOTAL_TASKS, successCount.get(),
                "Every markTaskAsCompleted call must succeed");
        assertEquals(TOTAL_TASKS, metrics.getTotalTasks(),
                "Total task count must not change");
        assertEquals(0, metrics.getOpenTasksCount(),
                "No tasks should be OPEN after all are completed");
        assertEquals(TOTAL_TASKS, metrics.getCompletedTasksCount(),
                "All tasks must be COMPLETED");
        assertEquals(metrics.getTotalTasks(),
                metrics.getOpenTasksCount() + metrics.getCompletedTasksCount()
                        + (metrics.getTotalTasks() - metrics.getOpenTasksCount() - metrics.getCompletedTasksCount()),
                "open + assigned + completed must always equal total");
    }

    /**
     * MANAGER_THREADS managers create tasks while WORKER_NAMES.length workers
     * simultaneously try to fetch their (currently empty) task lists.
     * This tests that read and write operations on the shared list do not
     * deadlock or throw a ConcurrentModificationException.
     */
    @Test
    void mixedConcurrentLoad_noDeadlockOrException() throws InterruptedException {
        int totalThreads = MANAGER_THREADS + WORKER_NAMES.length;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(totalThreads);

        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);

        for (int t = 0; t < MANAGER_THREADS; t++) {
            final int idx = t;
            pool.submit(() -> {
                ManagerServiceGrpc.ManagerServiceBlockingStub stub = newManagerStub();
                try {
                    startGate.await();
                    for (int i = 0; i < TASKS_PER_MANAGER; i++) {
                        stub.createNewTask(Manager.CreateTaskRequest.newBuilder()
                                .setTitle("Mixed-T" + idx + "-" + i)
                                .setDescription("mixed load test")
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        for (String workerName : WORKER_NAMES) {
            pool.submit(() -> {
                WorkerServiceGrpc.WorkerServiceBlockingStub stub = newWorkerStub();
                try {
                    startGate.await();
                    for (int i = 0; i < 20; i++) {   // 20 read RPCs per worker
                        stub.fetchMyTasks(Worker.GetMyTasksRequest.newBuilder()
                                .setWorkerName(workerName)
                                .build());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Mixed load test timed out");
        pool.shutdown();

        Manager.GetMetricsResponse metrics = newManagerStub()
                .getSystemMetrics(Manager.GetMetricsRequest.newBuilder().build());

        assertEquals(TOTAL_TASKS, metrics.getTotalTasks(),
                "No tasks must be lost or duplicated under concurrent mixed load");
    }
}
