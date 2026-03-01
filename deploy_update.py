import paramiko
import time
import sys
import os

HOST = "gaccloud.node2.serves.deldown.de"
PORT = 17865
USER = "root"
PASS = "yEAfnW!FDusRVKdPsV1d"

def deploy():
    print("Connecting...")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        # Force password auth by disabling keys/agent
        client.connect(
            HOST, 
            port=PORT, 
            username=USER, 
            password=PASS, 
            look_for_keys=False, 
            allow_agent=False
        )
    except Exception as e:
        print(f"Connection failed: {e}")
        return

    print("Connected.")

    sftp = client.open_sftp()

    # 1. Find main.py
    print("Finding main.py...")
    # Prioritize the known service path
    known_paths = ["/opt/gac-ml/main.py", "/root/gac-ml/main.py"]
    main_py_path = None
    
    for path in known_paths:
        try:
            sftp.stat(path)
            main_py_path = path
            print(f"Found main.py at priority location: {path}")
            break
        except FileNotFoundError:
            pass

    if not main_py_path:
        stdin, stdout, stderr = client.exec_command("find / -name main.py 2>/dev/null")
        paths = stdout.read().decode().strip().split('\n')
    
        # Filter for likely candidates (avoid /usr/lib/python... or temp dirs if possible)
        # Looking for /root/cloud-server/main.py or /opt/cloud-server/main.py etc.
        for p in paths:
            if "gac" in p or "cloud" in p:
                 main_py_path = p
                 break
        
        if not main_py_path and paths:
            main_py_path = paths[0]
            
    if not main_py_path:
        print("Could not find main.py!")
        # Fallback: check where service points to
        stdin, stdout, stderr = client.exec_command("systemctl cat gac-cloud | grep ExecStart")
        exec_line = stdout.read().decode().strip()
        if exec_line:
            # ExecStart=/usr/bin/python3 /path/to/main.py
            parts = exec_line.split()
            for part in parts:
                if part.endswith("main.py"):
                    main_py_path = part
                    print(f"Found path via service: {main_py_path}")
                    break
    
    if not main_py_path:
        print("Still could not find main.py. Aborting.")
        return

    print(f"Found main.py at: {main_py_path}")
    
    # 2. Read and Update main.py
    print("Reading remote main.py...")
    with sftp.open(main_py_path, 'r') as f:
        content = f.read().decode()

    modified = False
    
    # Check for FileResponse import
    if "from fastapi.responses import FileResponse" not in content:
        print("Adding FileResponse import...")
        # Add it after "from fastapi import ..."
        lines = content.split('\n')
        inserted = False
        for i, line in enumerate(lines):
            if "from fastapi import" in line:
                lines.insert(i+1, "from fastapi.responses import FileResponse")
                inserted = True
                break
        if not inserted:
             # Just add to top
             lines.insert(0, "from fastapi.responses import FileResponse")
             
        content = '\n'.join(lines)
        modified = True

    # Check for update endpoints
    if "/api/v2/update/version" not in content:
        print("Adding update endpoints...")
        
        # Append to end of file
        endpoints = """

# ==================== UPDATE SYSTEM ====================

@app.get("/api/v2/update/version")
async def check_update():
    # Check for plugin updates
    return {
        "available": True,
        "version": "1.1",
        "hash": "latest"
    }

@app.get("/api/v2/update/download")
async def download_update():
    # Download plugin update
    # Assuming updates folder is relative to main.py
    base_dir = os.path.dirname(os.path.abspath(__file__))
    file_path = os.path.join(base_dir, "updates", "GAC.jar")
    
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="Update file not found")
        
    return FileResponse(file_path, media_type="application/java-archive", filename="GAC.jar")
"""
        content += endpoints
        modified = True

    if modified:
        print("Writing updated main.py...")
        with sftp.open(main_py_path, 'w') as f:
            f.write(content)
    else:
        print("main.py already up to date.")

    # 3. Create updates directory
    remote_dir = os.path.dirname(main_py_path)
    updates_dir = os.path.join(remote_dir, "updates").replace("\\", "/")
    
    print(f"Creating updates directory at {updates_dir}...")
    try:
        sftp.mkdir(updates_dir)
    except IOError:
        pass # Probably exists

    # 4. Upload JAR
    local_jar = "target/original-gac-1.1.jar"
    remote_jar = f"{updates_dir}/GAC.jar"
    print(f"Uploading {local_jar} to {remote_jar}...")
    sftp.put(local_jar, remote_jar)
    print("Upload complete.")

    # 5. Restart Service
    print("Restarting service...")
    # Try to find the service name
    stdin, stdout, stderr = client.exec_command("systemctl list-units --type=service --state=running | grep gac")
    svc_out = stdout.read().decode()
    svc_name = "gac-cloud" # Default guess
    
    if "gac-cloud.service" in svc_out:
        svc_name = "gac-cloud"
    elif "gac.service" in svc_out:
        svc_name = "gac"
        
    print(f"Restarting {svc_name}...")
    stdin, stdout, stderr = client.exec_command(f"systemctl restart {svc_name}")
    exit_status = stdout.channel.recv_exit_status()
    if exit_status == 0:
        print("Service restarted successfully.")
    else:
        print(f"Failed to restart service. Exit code: {exit_status}")
        print(stderr.read().decode())

    client.close()
    print("Done!")

if __name__ == "__main__":
    deploy()
