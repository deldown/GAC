import os
import pty
import select
import time
import sys

def run_ssh(host, port, user, password, remote_cmd):
    pid, fd = pty.fork()
    if pid == 0:
        # Child: exec ssh
        # Force pseudo-terminal allocation with -tt to ensure we can interact if needed, 
        # though for a single command it might not be strictly necessary if we handle the password prompt right.
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
        # Parent: handle IO
        output = []
        password_sent = False
        
        while True:
            try:
                r, w, x = select.select([fd], [], [], 10)
                if not r:
                    # Timeout or done
                    break
                    
                data = os.read(fd, 1024)
                if not data:
                    break
                    
                chunk = data.decode('utf-8', errors='ignore')
                output.append(chunk)
                
                # Check for password prompt
                combined_output = "".join(output)
                if not password_sent and ("password:" in combined_output.lower() or "password" in chunk.lower()):
                    os.write(fd, (password + "\n").encode())
                    password_sent = True
                    # Reset output buffer to avoid re-triggering or printing password prompt too much
                    # (optional, but keeps logs clean)
                    
            except OSError:
                break
                
        os.close(fd)
        os.waitpid(pid, 0)
        return "".join(output)

host = "gaccloud.node2.serves.deldown.de"
port = "17865"
user = "root"
password = "yEAfnW!FDusRVKdPsV1d"

cmd = "ls -F /opt/gac-ml/"

print(run_ssh(host, port, user, password, cmd))
