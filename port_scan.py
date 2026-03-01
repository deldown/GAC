import socket

host = "gaccloud.node2.serves.deldown.de"
base_port = 17865
results = []

print(f"Scanning ports on {host} around {base_port}...")

for port in range(base_port - 5, base_port + 6):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(1.0)
    result = sock.connect_ex((host, port))
    if result == 0:
        print(f"Port {port}: OPEN")
        results.append(port)
    else:
        print(f"Port {port}: CLOSED/FILTERED")
    sock.close()
    
print("Scan complete.")
