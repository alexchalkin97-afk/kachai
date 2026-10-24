# Adds the local key ~/.ssh/kachai_vps.pub to root's authorized_keys on the VPS.
# Asks for the root password once; after that ssh works by key.
param([string]$Server = "5.187.1.101")

$key = Get-Content "$env:USERPROFILE\.ssh\kachai_vps.pub"
Write-Host ""
Write-Host "Connecting to root@$Server. Type the root password when asked (it is not shown while typing)."
Write-Host ""
$key | ssh -o StrictHostKeyChecking=accept-new "root@$Server" "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo KEY_ADDED_OK"
