import os
import pty
import select

def run_ssh(host, port, user, password, remote_cmd):
    pid, fd = pty.fork()
    if pid == 0:
        argv = [
            '/usr/bin/ssh',
            '-p', str(port),
            '-o', 'StrictHostKeyChecking=no',
            '-o', 'UserKnownHostsFile=/dev/null',
            f'{user}@{host}',
            remote_cmd
        ]
        os.execv(argv[0], argv)
    else:
        output = []
        password_sent = False
        
        while True:
            try:
                r, w, x = select.select([fd], [], [], 10)
                if not r:
                    break
                data = os.read(fd, 1024)
                if not data:
                    break
                chunk = data.decode('utf-8', errors='ignore')
                output.append(chunk)
                
                combined = "".join(output)
                if not password_sent and ("password:" in combined.lower() or "password" in chunk.lower()):
                    os.write(fd, (password + "\n").encode())
                    password_sent = True
            except OSError:
                break
        os.close(fd)
        os.waitpid(pid, 0)
        return "".join(output)

host = "gaccloud.node2.serves.deldown.de"
port = "17865"
user = "root"
password = "yEAfnW!FDusRVKdPsV1d"

# 1. Read main.py
cmd = "cat /opt/gac-ml/main.py"
print(run_ssh(host, port, user, password, cmd))
