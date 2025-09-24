#!/bin/bash

echo "Setting up Android build environment for Google Glass XE24..."

# Check if running with proper permissions
if [ "$EUID" -eq 0 ]; then
   echo "Please run without sudo. The script will request sudo when needed."
   exit 1
fi

# Install Java 17
echo "Installing Java JDK 17..."
sudo apt update
sudo apt install -y openjdk-17-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:$JAVA_HOME/bin

# Install Android command line tools
echo "Installing Android SDK..."
sudo apt install -y wget unzip

# Create Android SDK directory
mkdir -p ~/Android/Sdk
cd ~/Android/Sdk

# Download Android command line tools
echo "Downloading Android command line tools..."
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O commandlinetools.zip
unzip -q commandlinetools.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true
rm commandlinetools.zip

# Set Android environment variables
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools

# Accept licenses
yes | sdkmanager --licenses

# Install required SDK components for Google Glass XE24
echo "Installing Android SDK components for Google Glass XE24..."
sdkmanager "platform-tools" "platforms;android-19" "build-tools;34.0.0" "platforms;android-34"

# Add environment variables to bashrc
echo "Adding environment variables to ~/.bashrc..."
cat >> ~/.bashrc << 'EOL'

# Android SDK
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$JAVA_HOME/bin
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
export PATH=$PATH:$ANDROID_HOME/platform-tools
EOL

echo "Setup complete! Please run: source ~/.bashrc"
echo "Then you can build the APK with: ./gradlew assembleDebug"