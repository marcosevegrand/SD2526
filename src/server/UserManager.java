package server;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsável pelo ciclo de vida das credenciais de utilizador.
 * A intenção é fornecer uma base segura e atómica para operações de registo e login,
 * garantindo que não existem colisões de nomes de utilizador em acessos concorrentes.
 * Inclui agora persistência em disco para manter os dados após reinicialização.
 */
public class UserManager {

    private final Map<String, String> users = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final String filePath = "data/users.bin";

    /**
     * Inicializa o gestor de utilizadores e carrega os dados persistidos anteriormente.
     */
    public UserManager() {
        loadUsers();
    }

    /**
     * Carrega a base de dados de utilizadores a partir do disco.
     * A intenção é restaurar o estado das contas criadas em sessões anteriores.
     */
    private void loadUsers() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try (
            DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(f))
            )
        ) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                users.put(in.readUTF(), in.readUTF());
            }
        } catch (IOException e) {
            System.err.println(
                "ERRO: Falha ao carregar utilizadores: " + e.getMessage()
            );
        }
    }

    /**
     * Guarda o mapa atual de utilizadores em disco de forma atómica.
     * A intenção é garantir que os novos registos não se percam em caso de falha do servidor.
     */
    private void saveUsers() {
        try (
            DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filePath))
            )
        ) {
            out.writeInt(users.size());
            for (Map.Entry<String, String> entry : users.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        } catch (IOException e) {
            System.err.println(
                "ERRO: Falha ao persistir utilizadores: " + e.getMessage()
            );
        }
    }

    /**
     * Tenta persistir um novo conjunto de credenciais no mapa de utilizadores e no disco.
     * @param u Nome do utilizador único.
     * @param p Palavra-passe em texto limpo.
     * @return true se o utilizador foi criado, false se o nome já estiver reservado.
     */
    public boolean register(String u, String p) {
        lock.lock();
        try {
            if (users.containsKey(u)) return false;
            users.put(u, p);
            saveUsers();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se as credenciais fornecidas coincidem com os registos guardados.
     * @param u Nome de utilizador.
     * @param p Palavra-passe a validar.
     * @return true se os dados estiverem corretos, false caso contrário.
     */
    public boolean authenticate(String u, String p) {
        lock.lock();
        try {
            String stored = users.get(u);
            return stored != null && stored.equals(p);
        } finally {
            lock.unlock();
        }
    }
}
