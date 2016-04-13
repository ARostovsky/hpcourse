package server;

import com.google.protobuf.CodedOutputStream;
import communication.Protocol;
import communication.Protocol.*;
import com.google.protobuf.CodedInputStream;

import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

class ServerThread extends Thread {
    private final Socket socket;
    private final TaskManager taskManager;

    private final AtomicInteger activeCounter = new AtomicInteger(0);
    private final Object writeLock = new Object();

    ServerThread(Socket socket, TaskManager taskManager) {
        this.socket = socket;
        this.taskManager = taskManager;
    }

    @Override
    public void run() {
        while (true) {
            // Read message and start processing
            try {
                WrapperMessage inputMessage;
                try (InputStream input = socket.getInputStream()) {
                    inputMessage = readMessage(input);
                    if (!inputMessage.hasRequest())
                        throw new IOException("Message does not contain request");
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
                ServerRequest request = inputMessage.getRequest();
                processServerRequestAsync(request);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Wait for unfinished tasks
        synchronized (activeCounter) {
            while (activeCounter.get() > 0) {
                try {
                    activeCounter.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static WrapperMessage readMessage(InputStream input) throws IOException {
        CodedInputStream codedInputStream = CodedInputStream.newInstance(input);
        int messageSize = codedInputStream.readRawVarint32();
        if (messageSize <= 0) {
            throw new IOException("messageSize <= 0");
        }

        byte[] data = new byte[messageSize];
        if (input.read(data) < messageSize) {
            throw new IOException("Failed to read message bytes");
        }
        return WrapperMessage.parseFrom(data);
    }

    private static void writeMessage(WrapperMessage message, OutputStream output) throws IOException {
        CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(output);
        codedOutputStream.writeRawVarint32(message.getSerializedSize());
        message.writeTo(codedOutputStream);
    }


    /**
     * Calls {@code supplier} in separate thread and updates {@code activeCounter}.
     * Then writes response to {@code socket}
     *
     * @param supplier is supplier of ServerResponse
     */
    private void runAndWriteToSocketAsync(java.util.function.Supplier<ServerResponse.Builder> supplier) {
        activeCounter.incrementAndGet();
        new Thread(() -> {
            WrapperMessage message = WrapperMessage.newBuilder().setResponse(supplier.get()).build();
            synchronized (this.writeLock) {
                try (OutputStream output = socket.getOutputStream()) {
                    writeMessage(message, output);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            synchronized (activeCounter) {
                activeCounter.decrementAndGet();
                activeCounter.notifyAll();
            }
        }).start();
    }

    /**
     * Process request in separate thread and write response to socket
     */
    private void processServerRequestAsync(ServerRequest request) {
        if (request.hasSubmit()) {
            runAndWriteToSocketAsync(() -> {
                SubmitTaskResponse response = processSubmitTask(request.getSubmit());
                return ServerResponse.newBuilder().setSubmitResponse(response);
            });
        }
        if (request.hasSubscribe()) {
            runAndWriteToSocketAsync(() -> {
                SubscribeResponse response = processSubscribe(request.getSubscribe());
                return ServerResponse.newBuilder().setSubscribeResponse(response);
            });
        }
        if (request.hasList()) {
            runAndWriteToSocketAsync(() -> {
                ListTasksResponse response = processListTasks(request.getList(), request.getClientId());
                return ServerResponse.newBuilder().setListResponse(response);
            });
        }
    }

    private SubmitTaskResponse processSubmitTask(SubmitTask submitTask) {
        Protocol.SubmitTaskResponse.Builder response = Protocol.SubmitTaskResponse.newBuilder();

        Protocol.Task task = submitTask.getTask();
        int id = taskManager.addTask(task);
        response.setSubmittedTaskId(id).setStatus(Protocol.Status.OK);
        return response.build();
    }

    private SubscribeResponse processSubscribe(Subscribe subscribe) {
        Protocol.SubscribeResponse.Builder response = Protocol.SubscribeResponse.newBuilder();

        int id = subscribe.getTaskId();
        try {
            long result = taskManager.getResult(id);
            response.setValue(result);
            response.setStatus(Status.OK);
        } catch (IllegalArgumentException e) {
            response.setStatus(Status.ERROR);
        }

        return response.build();
    }

    private ListTasksResponse processListTasks(ListTasks listTasks, String clientId) {
        Protocol.ListTasksResponse.Builder response = Protocol.ListTasksResponse.newBuilder();
        try {
            for (Integer id : taskManager.getAllTasks()) {
                Protocol.ListTasksResponse.TaskDescription.Builder taskDescBuilder
                        = Protocol.ListTasksResponse.TaskDescription.newBuilder();
                taskDescBuilder.setClientId(clientId)
                        .setTaskId(id)
                        .setTask(taskManager.getTask(id));
                if (taskManager.hasResult(id))
                    taskDescBuilder.setResult(taskManager.getResult(id));

                response.addTasks(taskDescBuilder);
            }
            response.setStatus(Status.OK);
        } catch (Exception e) {
            response.setStatus(Status.ERROR);
        }
        return response.build();
    }
}