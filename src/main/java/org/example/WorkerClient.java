package org.example;

import de.mcc.TaskOuterClass.Task;
import de.mcc.Worker;
import de.mcc.WorkerServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.util.List;

import static org.example.Helper.readLine;

/**
 * Team-member (Worker) client – list own tasks and mark them as completed.
 */
public class WorkerClient {

    private final WorkerServiceGrpc.WorkerServiceBlockingStub stub;
    private final ManagedChannel channel;
    private final String workerName;
    private boolean running = true;

    public static void main() {
        String name = readLine("Whats your name?");
        WorkerClient worker = new WorkerClient("localhost", 9090, name);
        worker.start();
    }

    public WorkerClient(String host, int port, String workerName) {
        channel = Grpc.newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create()).build();
        stub = WorkerServiceGrpc.newBlockingStub(channel);
        this.workerName = workerName;
    }

    public void start() {
        System.out.println("=== Worker Client [" + workerName + "] – Type '!help' to get started ===");
        while (running) {
            String input = readLine("\nCommand");
            handleCommand(input);
        }
        channel.shutdown();
    }

    private void handleCommand(String input) {
        if (input == null || input.isBlank()) return;
        switch (input) {
            case "!help" -> printHelp();
            case "!exit" -> { System.out.println("Goodbye!"); running = false; }
            case "!myTasks" -> handleMyTasks();
            case "!completeTask" -> handleCompleteTask();
            default -> System.out.println("Unknown command '" + input + "'. Type '!help'.");
        }
    }

    private void printHelp() {
        System.out.println("""
                Available commands:
                  !help         – show this help
                  !exit         – quit
                  !myTasks      – list your assigned tasks
                  !completeTask – mark a task as completed""");
    }

    private void handleMyTasks() {
        try {
            Worker.GetMyTasksResponse resp = stub.fetchMyTasks(
                    Worker.GetMyTasksRequest.newBuilder()
                            .setWorkerName(workerName)
                            .build());
            List<Task> tasks = resp.getAssignedTasksList();
            if (tasks.isEmpty()) {
                System.out.println("No tasks assigned to you.");
                return;
            }
            System.out.println("\n--- Your Tasks ---");
            for (Task t : tasks) {
                System.out.println("[" + t.getTaskId() + "] " + t.getTitle() + " ("+t.getStatus().name()+"): " + t.getDescription());
            }
        } catch (StatusRuntimeException e) {
            System.out.println("Error: " + e.getStatus().getDescription());
        }
    }

    private void handleCompleteTask() {
        String idStr = readLine("Task ID");
        try {
            int taskId = Integer.parseInt(idStr);
            Worker.CompleteTaskResponse resp = stub.markTaskAsCompleted(
                    Worker.CompleteTaskRequest.newBuilder()
                            .setTaskId(taskId)
                            .setWorkerName(workerName)
                            .build());
            if (resp.getSuccess()) {
                System.out.println("Task [" + resp.getCompletedTask().getTaskId() + "] " + resp.getCompletedTask().getTitle() + "marked as COMPLETED");
            } else {
                System.out.println("Could not complete task (not found or already completed).");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid task ID: " + idStr);
        } catch (StatusRuntimeException e) {
            System.out.println("Error: " + e.getStatus().getDescription());
        }
    }
}
