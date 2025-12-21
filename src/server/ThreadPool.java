package server;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementação de um Pool de Threads que utiliza Composição (Runnable) em vez de Herança.
 * Mantém o uso de ReentrantLock e Condition para gestão de concorrência.
 */
public class ThreadPool {

    private final LinkedList<Runnable> taskQueue;
    private final List<Thread> workers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private boolean isShutdown = false;

    /**
     * Inicializa o pool criando threads que executam a lógica de processamento de tarefas.
     * @param nThreads Número de threads no pool.
     */
    public ThreadPool(int nThreads) {
        this.taskQueue = new LinkedList<>();
        this.workers = new ArrayList<>(nThreads);

        for (int i = 0; i < nThreads; i++) {
            // Criamos a thread passando a lógica (Runnable) no construtor
            Thread worker = new Thread(this::workerLoop, "PoolWorker-" + i);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * Lógica principal de cada thread trabalhadora.
     * Consome tarefas da fila enquanto o pool estiver ativo.
     */
    private void workerLoop() {
        while (true) {
            Runnable task = null;

            lock.lock();
            try {
                // Espera por tarefas ou pelo sinal de encerramento
                while (taskQueue.isEmpty() && !isShutdown) {
                    try {
                        notEmpty.await();
                    } catch (InterruptedException e) {
                        // Se a thread for interrompida e o pool estiver a fechar, termina a execução
                        if (isShutdown) return;
                    }
                }

                // Condição de saída: pool fechado e sem tarefas pendentes
                if (isShutdown && taskQueue.isEmpty()) return;

                if (!taskQueue.isEmpty()) {
                    task = taskQueue.poll();
                }
            } finally {
                lock.unlock();
            }

            // Executa a tarefa fora do lock
            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    System.err.println(
                        "[" +
                            Thread.currentThread().getName() +
                            "] Erro na execução: " +
                            e.getMessage()
                    );
                }
            }
        }
    }

    /**
     * Submete uma tarefa para execução no pool.
     */
    public void submit(Runnable task) {
        lock.lock();
        try {
            if (isShutdown) return;
            taskQueue.add(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra o pool e interrompe as threads trabalhadoras.
     */
    public void shutdown() {
        lock.lock();
        try {
            isShutdown = true;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }

        for (Thread worker : workers) {
            worker.interrupt();
        }
    }
}
