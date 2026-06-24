package org.example;

import static org.example.Helper.readLine;

import de.mcc.Manager;
import de.mcc.ManagerServiceGrpc;
import de.mcc.TaskOuterClass.Task;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

/**
 * Chief (Manager) client – create tasks, assign them to workers, view metrics.
 */
public class ManagerClient {
    private final ManagerServiceGrpc.ManagerServiceBlockingStub stub;
    private final ManagedChannel channel;
    private boolean running = true;

    public static void main() {
        ManagerClient manager = new ManagerClient("localhost", 9090);
        manager.start();
    }

    public ManagerClient(String host, int port) {
        channel = Grpc.newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create()).build();
        stub = ManagerServiceGrpc.newBlockingStub(channel);
    }

    public void start() {
        System.out.println("=== Manager Client – Type '!help' to get started ===");
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
            case "!createTask" -> handleCreateTask();
            case "!assignTask" -> handleAssignTask();
            case "!metrics" -> handleGetMetrics();
            default -> System.out.println("Unknown command '" + input + "'. Type '!help'.");
        }
    }

    private void printHelp() {
        System.out.println("""
                Available commands:
                  !help       – show this help
                  !exit       – quit
                  !createTask – create a new task
                  !assignTask – assign a task to a team member
                  !metrics    – display system metrics""");
    }

    private void handleCreateTask() {
        String title = readLine("Title");
        String description = readLine("Description");
        try {
            Manager.CreateTaskResponse resp = stub.createNewTask(Manager.CreateTaskRequest.newBuilder()
                    .setTitle(title)
                    .setDescription(description)
                    .build());
            Task t = resp.getCreatedTask();
            System.out.println("Task created: [" + t.getTaskId() + "] " + t.getTitle());
        } catch (StatusRuntimeException e) {
            System.out.println("Error: " + e.getStatus().getDescription());
        }
    }

    private void handleAssignTask() {
        String idStr = readLine("Task ID");
        String assignee = readLine("Assignee name");
        try {
            int taskId = Integer.parseInt(idStr);
            Manager.AssignTaskResponse resp = stub.assignTaskToWorker(Manager.AssignTaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setAssigneeName(assignee)
                    .build());
            if (resp.getSuccess()) {
                Task t = resp.getUpdatedTask();
                System.out.println("Task [" + t.getTaskId() + "] " + t.getTitle() + " assigned to " + t.getAssigneeName());
            } else {
                System.out.println("Task not found.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid task ID: " + idStr);
        } catch (StatusRuntimeException e) {
            System.out.println("Error: " + e.getStatus().getDescription());
        }
    }

    private void handleGetMetrics() {
        try {
            Manager.GetMetricsResponse resp = stub.getSystemMetrics(Manager.GetMetricsRequest.newBuilder().build());
            System.out.println("\n=== System Metrics ===");
            System.out.println("Total tasks: " + resp.getTotalTasks());
            System.out.println("Open:        " + resp.getOpenTasksCount());
            System.out.println("Completed:   " + resp.getCompletedTasksCount());
            int assigned = resp.getTotalTasks() - resp.getOpenTasksCount() - resp.getCompletedTasksCount();
            System.out.println("In progress: " + assigned);
        } catch (StatusRuntimeException e) {
            System.out.println("Error: " + e.getStatus().getDescription());
        }
    }
}
