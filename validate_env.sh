#!/usr/bin/env bash

# This script automates the verification and provisioning of the Android development environment.
# Design Rationale:
#   - Root execution is blocked to avoid incorrect permission sets on user-owned paths (like Android SDK or Git hooks).
#   - Inline sudo escalation is strictly reserved for system package managers (apt, snap).
#   - Check-only mode (--check / -c) performs read-only inspection of the current environment state without modifying files or prompting for sudo.

set -euo pipefail

CHECK_ONLY=false
FAILED_CHECKS=0

# Parse options
while [[ "$#" -gt 0 ]]; do
  case $1 in
    -c|--check) CHECK_ONLY=true ;;
    *) echo "Unknown parameter: $1" >&2; exit 1 ;;
  esac
  shift
done

# Block root execution to safeguard file ownership
if [ "$EUID" -eq 0 ]; then
  echo "Error: Running as root is blocked. Run as a regular user with sudo privileges." >&2
  exit 1
fi

SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
CMDLINE_TOOLS_VER="14742923"

# Formatting utilities
log_info() {
  echo -e "\e[34m[INFO]\e[0m $1"
}
log_success() {
  echo -e "\e[32m[SUCCESS]\e[0m $1"
}
log_warning() {
  echo -e "\e[33m[WARNING]\e[0m $1"
}
log_error() {
  echo -e "\e[31m[ERROR]\e[0m $1"
}

# 1. Java 17 Check & Install
check_java_17() {
  log_info "Checking Java 17..."
  if ! command -v java >/dev/null 2>&1; then
    log_error "java command not found in PATH."
    return 1
  fi

  local java_ver
  java_ver=$(java -version 2>&1 | head -n 1)
  if [[ "$java_ver" =~ "17" ]]; then
    log_success "Active Java 17 version validated: $java_ver"
    return 0
  else
    log_error "Incorrect Java version active: $java_ver. Java 17 is required."
    return 1
  fi
}

install_java_17() {
  log_info "Installing OpenJDK 17..."
  sudo apt-get update
  sudo apt-get install -y openjdk-17-jdk
}

# 2. KVM Hardware Virtualization Check & Install
check_kvm() {
  log_info "Checking CPU virtualization..."
  if [ -f /proc/cpuinfo ]; then
    if grep -qE "vmx|svm" /proc/cpuinfo; then
      log_success "CPU virtualization (vmx/svm) is enabled."
    else
      log_error "CPU virtualization is not supported or is disabled in BIOS."
      return 1
    fi
  else
    log_warning "/proc/cpuinfo is not accessible."
  fi

  log_info "Checking /dev/kvm access permissions..."
  if [ -e /dev/kvm ]; then
    if [ -w /dev/kvm ]; then
      log_success "Writable access to /dev/kvm verified."
    else
      log_error "User '$USER' lacks write access to /dev/kvm."
      return 1
    fi
  else
    log_error "/dev/kvm does not exist. Hardware virtualization drivers may not be loaded."
    return 1
  fi

  log_info "Checking KVM/Libvirt group membership..."
  local groups_list
  groups_list=$(id -nG)
  local kvm_ok=true
  if ! echo "$groups_list" | grep -qE "\bkvm\b"; then
    log_error "User '$USER' is not a member of the 'kvm' group."
    kvm_ok=false
  fi
  if ! echo "$groups_list" | grep -qE "\blibvirt\b"; then
    log_error "User '$USER' is not a member of the 'libvirt' group."
    kvm_ok=false
  fi
  if [ "$kvm_ok" = "false" ]; then
    return 1
  fi

  log_info "Checking virtualization packages..."
  local pkg_missing=false
  for pkg in qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils cpu-checker; do
    if ! dpkg -l "$pkg" >/dev/null 2>&1; then
      log_error "Required package '$pkg' is missing."
      pkg_missing=true
    fi
  done
  if [ "$pkg_missing" = "true" ]; then
    return 1
  fi

  log_success "KVM virtualization is fully configured."
  return 0
}

install_kvm() {
  log_info "Installing KVM virtualization packages..."
  sudo apt-get update
  sudo apt-get install -y qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils cpu-checker

  log_info "Enrolling '$USER' into 'kvm' and 'libvirt' groups..."
  sudo usermod -aG kvm "$USER"
  sudo usermod -aG libvirt "$USER"

  log_warning "Group enrollment updated. You MUST restart your session or run 'newgrp kvm' to apply updates."
}

# 3. Android Studio Snap Check & Install
check_android_studio() {
  log_info "Checking Android Studio snap installation..."
  if command -v snap >/dev/null 2>&1; then
    if snap list android-studio >/dev/null 2>&1; then
      log_success "Android Studio snap installation verified."
      return 0
    fi
  fi
  log_error "Android Studio snap package is not installed."
  return 1
}

install_android_studio() {
  log_info "Installing Android Studio snap..."
  sudo snap install android-studio --classic
}

# 4. Android SDK CLI Tools Check & Install
check_android_sdk_cli_tools() {
  log_info "Checking Android SDK command-line tools..."
  if [ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    log_success "sdkmanager executable found at $SDK_DIR/cmdline-tools/latest/bin/sdkmanager."
    return 0
  fi
  log_error "sdkmanager executable is missing at $SDK_DIR/cmdline-tools/latest/bin/sdkmanager."
  return 1
}

install_android_sdk_cli_tools() {
  log_info "Verifying internet connection to Google repository..."
  if ! ping -c 1 -W 2 dl.google.com >/dev/null 2>&1; then
    log_error "Google repository is unreachable. Check internet settings."
    return 1
  fi

  log_info "Downloading Android SDK Command-Line Tools version $CMDLINE_TOOLS_VER..."
  local temp_dir
  temp_dir="/home/philong/wallpaper-scheduler/.tmp"
  mkdir -p "$temp_dir"
  local zip_path="$temp_dir/commandlinetools.zip"

  if ! wget -q --show-progress "$CMDLINE_TOOLS_URL" -O "$zip_path"; then
    log_error "Download failed from $CMDLINE_TOOLS_URL."
    return 1
  fi

  log_info "Extracting tools..."
  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools/latest"

  local extract_dir="$temp_dir/extracted"
  mkdir -p "$extract_dir"
  if ! unzip -q "$zip_path" -d "$extract_dir"; then
    log_error "Extraction of command-line tools failed."
    return 1
  fi

  mv "$extract_dir/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$temp_dir"

  log_success "Android SDK Command-Line Tools version $CMDLINE_TOOLS_VER installed."
  return 0
}

# 5. Shell environment configuration
check_shell_variables() {
  log_info "Checking shell environment path configurations..."
  if [ -n "${ANDROID_HOME:-}" ]; then
    log_success "ANDROID_HOME path variables are active ($ANDROID_HOME)."
    return 0
  fi

  for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
    if [ -f "$rc" ] && grep -q "export ANDROID_HOME=" "$rc"; then
      log_success "ANDROID_HOME path configuration found in $rc."
      return 0
    fi
  done

  log_error "ANDROID_HOME is not exported and no references found in ~/.bashrc or ~/.zshrc."
  return 1
}

configure_shell_variables() {
  echo "Android SDK environment paths need configuration."
  echo "Choose shell initialization file to modify:"
  echo "1) ~/.bashrc"
  echo "2) ~/.zshrc"
  echo "3) Skip setup"
  read -rp "Choice [1-3]: " choice
  local rc_file=""
  case $choice in
    1) rc_file="$HOME/.bashrc" ;;
    2) rc_file="$HOME/.zshrc" ;;
    *) log_info "Configuration skipped." ; return 0 ;;
  esac

  if [ -n "$rc_file" ]; then
    if [ -f "$rc_file" ]; then
      if grep -q "export ANDROID_HOME=" "$rc_file"; then
        log_info "Configuration is already configured in $rc_file."
      else
        echo "" >> "$rc_file"
        echo "# Android SDK configuration paths" >> "$rc_file"
        echo "export ANDROID_HOME=\"\$HOME/Android/Sdk\"" >> "$rc_file"
        echo "export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\"" >> "$rc_file"
        log_success "Updated config file $rc_file. Run 'source $rc_file' to reload paths."
      fi
    else
      log_error "Config file $rc_file is not present."
      return 1
    fi
  fi
  return 0
}

# 6. SDK Packages Check & Install
check_android_packages() {
  log_info "Checking SDK dependency packages..."
  local pkg_missing=false

  if [ ! -x "$SDK_DIR/platform-tools/adb" ]; then
    log_error "Missing SDK package: platform-tools (adb)."
    pkg_missing=true
  fi
  if [ ! -f "$SDK_DIR/platforms/android-33/android.jar" ]; then
    log_error "Missing SDK package: platforms;android-33."
    pkg_missing=true
  fi
  if [ ! -x "$SDK_DIR/build-tools/33.0.0/aapt" ]; then
    log_error "Missing SDK package: build-tools;33.0.0."
    pkg_missing=true
  fi

  if [ "$pkg_missing" = "true" ]; then
    return 1
  fi

  log_success "All SDK package dependencies verified."
  return 0
}

install_android_packages() {
  log_info "Accepting licensing terms..."
  yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

  log_info "Provisioning SDK packages (platform-tools, platforms;android-33, build-tools;33.0.0)..."
  if "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-33" "build-tools;33.0.0"; then
    log_success "SDK package dependencies successfully installed."
    return 0
  else
    log_error "SDK package dependencies installation failed."
    return 1
  fi
}

# 7. pre-commit installation check
check_pre_commit_tools() {
  log_info "Checking pre-commit package..."
  if ! command -v pre-commit >/dev/null 2>&1; then
    log_error "pre-commit command not found."
    return 1
  fi
  log_success "pre-commit package is available."

  log_info "Checking git hook status..."
  if [ -x ".git/hooks/pre-commit" ]; then
    log_success "Git pre-commit hook is active."
    return 0
  else
    log_error "Git pre-commit hook is inactive or missing."
    return 1
  fi
}

install_pre_commit_tools() {
  if ! command -v pre-commit >/dev/null 2>&1; then
    log_info "Installing pre-commit system package..."
    sudo apt-get update
    sudo apt-get install -y pre-commit
  fi

  log_info "Configuring git hook..."
  if pre-commit install; then
    log_success "Git pre-commit hook activated."
    return 0
  else
    log_error "Git pre-commit hook activation failed."
    return 1
  fi
}

# MAIN EXECUTION ROUTINE

# 1. Java 17
if ! check_java_17; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    install_java_17
    if ! check_java_17; then
      log_error "Java 17 setup validation failed."
      exit 1
    fi
    FAILED_CHECKS=$((FAILED_CHECKS - 1))
  fi
fi

# 2. KVM Hardware Virtualization
if ! check_kvm; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    install_kvm
    # Skip re-verification since group updates require session re-entry, but decrement check counter
    FAILED_CHECKS=$((FAILED_CHECKS - 1))
  fi
fi

# 3. Android Studio Snap
if ! check_android_studio; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    install_android_studio
    if ! check_android_studio; then
      log_error "Android Studio snap validation failed."
      exit 1
    fi
    FAILED_CHECKS=$((FAILED_CHECKS - 1))
  fi
fi

# 4. Android SDK CLI Tools
if ! check_android_sdk_cli_tools; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    if install_android_sdk_cli_tools; then
      FAILED_CHECKS=$((FAILED_CHECKS - 1))
    else
      log_error "Android SDK CLI Tools validation failed."
      exit 1
    fi
  fi
fi

# 5. Shell Variables
if ! check_shell_variables; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    if configure_shell_variables; then
      FAILED_CHECKS=$((FAILED_CHECKS - 1))
    fi
  fi
fi

# 6. Android SDK Packages
if ! check_android_packages; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    if install_android_packages; then
      FAILED_CHECKS=$((FAILED_CHECKS - 1))
    else
      log_error "Android SDK packages validation failed."
      exit 1
    fi
  fi
fi

# 7. pre-commit
if ! check_pre_commit_tools; then
  FAILED_CHECKS=$((FAILED_CHECKS + 1))
  if [ "$CHECK_ONLY" = "false" ]; then
    if install_pre_commit_tools; then
      FAILED_CHECKS=$((FAILED_CHECKS - 1))
    else
      log_error "pre-commit hook validation failed."
      exit 1
    fi
  fi
fi

if [ "$FAILED_CHECKS" -gt 0 ]; then
  log_error "Environment verification completed with $FAILED_CHECKS failures."
  exit 1
else
  log_success "Environment verification succeeded. All components configured."
  exit 0
fi
