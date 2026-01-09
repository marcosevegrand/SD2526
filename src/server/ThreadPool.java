package server;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pool de threads personalizado para processamento de pedidos.
 *
 * Implementa um padrão produtor-consumidor onde tarefas são submetidas
 * por threads de leitura e executadas por um conjunto fixo de workers.
 * Esta abordagem evita a criação excessiva de threads e permite controlar
 * a carga do sistema.
 */
public class ThreadPool {

    private final LinkedList<Runnable> taskQueue;
    private final List<Thread> workers;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private boolean isShutdown = false;

    /**
     * Cria um pool com o número especificado de threads trabalhadoras.
     *
     * @param nThreads Número de threads no pool
     */
    public ThreadPool(int nThreads) {
        this.taskQueue = new LinkedList<>();
        this.workers = new ArrayList<>(nThreads);

        for (int i = 0; i < nThreads; i++) {
            Thread worker = new Thread(this::workerLoop, "PoolWorker-" + i);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * Ciclo principal de cada thread trabalhadora.
     * Aguarda por tarefas na fila e executa-as sequencialmente até que
     * o pool seja encerrado. Exceções durante a execução são capturadas
     * e registadas para não afetar outras tarefas.
     */
    private void workerLoop() {
        while (true) {
            Runnable task;

            lock.lock();
            try {
                while (taskQueue.isEmpty() && !isShutdown) {
                    try {
                        notEmpty.await();
                    } catch (InterruptedException e) {
                        if (isShutdown) {
                            return;
                        }
                    }
                }

                if (isShutdown && taskQueue.isEmpty()) {
                    return;
                }

                task = taskQueue.poll();
            } finally {
                lock.unlock();
            }

            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    System.err.println(
                            "[" + Thread.currentThread().getName() + "] Erro na execução: " + e.getMessage()
                    );
                }
            }
        }
    }

    /**
     * Submete uma tarefa para execução assíncrona.
     * A tarefa é adicionada à fila e uma thread trabalhadora disponível
     * é notificada para a processar.
     *
     * @param task Tarefa a executar
     */
    public void submit(Runnable task) {
        lock.lock();
        try {
            if (isShutdown) {
                return;
            }
            taskQueue.add(task);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra o pool de forma ordenada.
     * Sinaliza todas as threads para terminarem e interrompe aquelas
     * que estejam bloqueadas em espera.
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