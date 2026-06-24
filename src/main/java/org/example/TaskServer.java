package org.example;

import de.mcc.Manager;
import de.mcc.ManagerServiceGrpc;
import de.mcc.Worker;
import de.mcc.WorkerServiceGrpc;
import de.mcc.TaskOuterClass.TaskStatus;
import de.mcc.TaskOuterClass.Task;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskServer {
    private static final List<Task> tasks = new ArrayList<>();
    private static final AtomicInteger idCounter = new AtomicInteger(1);

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = Grpc
                .newServerBuilderForPort(9090, InsecureServerCredentials.create())
                .addService(new ManagerServiceImpl())
                .addService(new WorkerServiceImpl())
                .build();

        server.start();
        System.out.println("=== TaskServer started on port 9090 ===");
        server.awaitTermination();
    }

    static class ManagerServiceImpl extends ManagerServiceGrpc.ManagerServiceImplBase {

        @Override
        public void createNewTask(Manager.CreateTaskRequest request,
                                  StreamObserver<Manager.CreateTaskResponse> responseObserver) {
            synchronized (tasks) {
                Task newTask = Task.newBuilder()
                        .setTaskId(idCounter.getAndIncrement())
                        .setTitle(request.getTitle())
                        .setDescription(request.getDescription())
                        .setStatus(TaskStatus.OPEN)
                        .build();
                tasks.add(newTask);

                responseObserver.onNext(Manager.CreateTaskResponse.newBuilder()
                        .setCreatedTask(newTask)
                        .build());
                responseObserver.onCompleted();
            }
        }

        @Override
        public void assignTaskToWorker(Manager.AssignTaskRequest request,
                                       StreamObserver<Manager.AssignTaskResponse> responseObserver) {
            synchronized (tasks) {
                int idx = indexOfTask(request.getTaskId());
                if (idx < 0) {
                    responseObserver.onNext(Manager.AssignTaskResponse.newBuilder()
                            .setSuccess(false)
                            .build());
                    responseObserver.onCompleted();
                    return;
                }

                Task updated = tasks.get(idx).toBuilder()
                        .setAssigneeName(request.getAssigneeName())
                        .setStatus(TaskStatus.ASSIGNED)
                        .build();
                tasks.set(idx, updated);

                responseObserver.onNext(Manager.AssignTaskResponse.newBuilder()
                        .setUpdatedTask(updated)
                        .setSuccess(true)
                        .build());
                responseObserver.onCompleted();
            }
        }

        @Override
        public void getSystemMetrics(Manager.GetMetricsRequest request,
                                     StreamObserver<Manager.GetMetricsResponse> responseObserver) {
            synchronized (tasks) {
                long open = tasks.stream().filter(t -> t.getStatus() == TaskStatus.OPEN).count();
                long completed = tasks.stream().filter(t -> t.getStatus() == TaskStatus.COMPLETED).count();

                responseObserver.onNext(Manager.GetMetricsResponse.newBuilder()
                        .setTotalTasks(tasks.size())
                        .setOpenTasksCount((int) open)
                        .setCompletedTasksCount((int) completed)
                        .build());
                responseObserver.onCompleted();
            }
        }
    }

    static class WorkerServiceImpl extends WorkerServiceGrpc.WorkerServiceImplBase {

        @Override
        public void fetchMyTasks(Worker.GetMyTasksRequest request,
                                 StreamObserver<Worker.GetMyTasksResponse> responseObserver) {
            synchronized (tasks) {
                List<Task> mine = tasks.stream()
                        .filter(t -> t.getAssigneeName().equalsIgnoreCase(request.getWorkerName()))
                        .toList();

                responseObserver.onNext(Worker.GetMyTasksResponse.newBuilder()
                        .addAllAssignedTasks(mine)
                        .build());
                responseObserver.onCompleted();
            }
        }

        @Override
        public void markTaskAsCompleted(Worker.CompleteTaskRequest request,
                                        StreamObserver<Worker.CompleteTaskResponse> responseObserver) {
            synchronized (tasks) {
                int idx = indexOfTask(request.getTaskId());
                if (idx < 0) {
                    responseObserver.onNext(Worker.CompleteTaskResponse.newBuilder()
                            .setSuccess(false)
                            .build());
                    responseObserver.onCompleted();
                    return;
                }

                Task task = tasks.get(idx);
                // Only the assigned worker is allowed to complete the task
                if (!task.getAssigneeName().equalsIgnoreCase(request.getWorkerName())) {
                    responseObserver.onError(Status.PERMISSION_DENIED
                            .withDescription("Task is not assigned to " + request.getWorkerName())
                            .asRuntimeException());
                    return;
                }

                Task completed = task.toBuilder()
                        .setStatus(TaskStatus.COMPLETED)
                        .build();
                tasks.set(idx, completed);

                responseObserver.onNext(Worker.CompleteTaskResponse.newBuilder()
                        .setSuccess(true)
                        .setCompletedTask(completed)
                        .build());
                responseObserver.onCompleted();
            }
        }
    }

    /** Resets shared state – called by tests before each run. */
    static void reset() {
        synchronized (tasks) {
            tasks.clear();
            idCounter.set(1);
        }
    }

    private static int indexOfTask(int taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getTaskId() == taskId) return i;
        }
        return -1;
    }
}

