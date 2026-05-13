include devices.mk

VARIANT ?= debug

ifeq ($(VARIANT), release)
  PHONE_PKG  = io.github.pranavm716.transittime
  WEAR_PKG   = io.github.pranavm716.transittime
  PHONE_APK  = app/build/outputs/apk/release/app-release.apk
  WEAR_APK   = wear/build/outputs/apk/release/wear-release.apk
  GRADLE_APP = :app:assembleRelease
  GRADLE_WEAR= :wear:assembleRelease
else
  PHONE_PKG  = io.github.pranavm716.transittime.dev
  WEAR_PKG   = io.github.pranavm716.transittime.dev
  PHONE_APK  = app/build/outputs/apk/debug/app-debug.apk
  WEAR_APK   = wear/build/outputs/apk/debug/wear-debug.apk
  GRADLE_APP = :app:assembleDebug
  GRADLE_WEAR= :wear:assembleDebug
endif

.PHONY: reinstall reinstall-phone reinstall-watch --phone --watch

reinstall:
	-adb -s $(PHONE) shell pm clear $(PHONE_PKG)
	-adb -s $(PHONE) shell pm revoke $(PHONE_PKG) android.permission.POST_NOTIFICATIONS
	-adb -s $(PHONE) shell cmd deviceidle whitelist -$(PHONE_PKG)
	-adb -s $(WATCH) shell pm clear $(WEAR_PKG)
	-adb -s $(WATCH) shell pm revoke $(WEAR_PKG) android.permission.POST_NOTIFICATIONS
	-adb -s $(WATCH) shell cmd deviceidle whitelist -$(WEAR_PKG)
	gradlew.bat $(GRADLE_APP) $(GRADLE_WEAR)
	adb -s $(PHONE) install -r $(PHONE_APK)
	adb -s $(WATCH) install -r $(WEAR_APK)

reinstall-phone --phone:
	-adb -s $(PHONE) shell pm clear $(PHONE_PKG)
	-adb -s $(PHONE) shell pm revoke $(PHONE_PKG) android.permission.POST_NOTIFICATIONS
	-adb -s $(PHONE) shell cmd deviceidle whitelist -$(PHONE_PKG)
	gradlew.bat $(GRADLE_APP)
	adb -s $(PHONE) install -r $(PHONE_APK)

reinstall-watch --watch:
	-adb -s $(WATCH) shell pm clear $(WEAR_PKG)
	-adb -s $(WATCH) shell pm revoke $(WEAR_PKG) android.permission.POST_NOTIFICATIONS
	-adb -s $(WATCH) shell cmd deviceidle whitelist -$(WEAR_PKG)
	gradlew.bat $(GRADLE_WEAR)
	adb -s $(WATCH) install -r $(WEAR_APK)
