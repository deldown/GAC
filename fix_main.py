import re

def fix_main():
    with open("local_main.py", "r", encoding="utf-8") as f:
        content = f.read()

    # 1. Remove SSH Banner
    # Banner is frame of unicode chars. 
    # Starts with ╔... ends with ╚...╝
    # It might be at the start.
    # Simple heuristic: remove everything before the first "import" or "#!"
    if "#!/usr/bin/env python3" in content:
        parts = content.split("#!/usr/bin/env python3")
        content = "#!/usr/bin/env python3" + parts[1]
    
    # 2. Extract Update System Block
    update_marker = "# ==================== UPDATE SYSTEM ===================="
    if update_marker in content:
        parts = content.split(update_marker)
        main_code = parts[0]
        update_code = update_marker + parts[1]
        
        # 3. Find if __name__ block
        if 'if __name__ == "__main__":' in main_code:
            main_parts = main_code.split('if __name__ == "__main__":')
            pre_main = main_parts[0]
            main_block = 'if __name__ == "__main__":' + main_parts[1]
            
            # 4. Reassemble: Pre-Main + Update Code + Main Block
            new_content = pre_main + "\n" + update_code + "\n\n" + main_block
            
            with open("local_main_fixed.py", "w", encoding="utf-8") as f:
                f.write(new_content)
            print("Fixed successfully.")
        else:
            print("Could not find main block.")
    else:
        print("Could not find update block.")

if __name__ == "__main__":
    fix_main()
