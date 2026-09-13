FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 \
    libasound2t64 libglu1-mesa libgl1 libxrandr2 libxdamage1 libxkbcommon0 \
    libdbus-1-3 libx11-6 libxext6 libxfixes3 libxrender1 ca-certificates \
    python3 openjdk-17-jdk-headless curl unzip socat \
    && rm -rf /var/lib/apt/lists/*
ENV ANDROID_SDK_ROOT=/sdk ANDROID_HOME=/sdk
ENV PATH=$PATH:/sdk/cmdline-tools/latest/bin:/sdk/platform-tools:/sdk/emulator

# The SDK and the AVD are baked in.
# The system image tracks compileSdk 35 (includes Play Store)
# x86_64 because there is no ARM image for 35
ARG CMDLINE_TOOLS=13114758
ARG SYSTEM_IMAGE="system-images;android-35;google_apis_playstore;x86_64"
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
    && mkdir -p /sdk/cmdline-tools \
    && unzip -q /tmp/cmdline-tools.zip -d /sdk/cmdline-tools \
    && mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "emulator" "$SYSTEM_IMAGE"

# pixel_6 gives 1080x2400 at 420dpi, the profile the eval tasks were written against. Only
# the data partition needs changing: 2G fills up part way through a suite that installs and
# uninstalls repeatedly. avdmanager ignores ANDROID_AVD_HOME, so the AVD is created in $HOME
RUN echo no | avdmanager create avd -n agenttest -k "$SYSTEM_IMAGE" -d pixel_6 \
    && sed -i 's/^disk.dataPartition.size=.*/disk.dataPartition.size=6G/' \
         /root/.android/avd/agenttest.avd/config.ini

COPY start.sh /start.sh
COPY shotserver.py /shotserver.py
RUN chmod +x /start.sh
# 5555 adb, 6081 the live view in shotserver.py
EXPOSE 5555 6081
CMD ["/start.sh"]
