include devices.mk

PHONE_PKG = io.github.pranavm716.transittime.dev
WEAR_PKG  = io.github.pranavm716.transittime.dev
PHONE_APK = app/build/outputs/apk/debug/app-debug.apk
WEAR_APK  = wear/build/outputs/apk/debug/wear-debug.apk

reinstall:
	adb -s $(PHONE) shell pm clear $(PHONE_PKG)
	adb -s $(PHONE) shell pm revoke $(PHONE_PKG) android.permission.POST_NOTIFICATIONS
	adb -s $(PHONE) shell cmd deviceidle whitelist -$(PHONE_PKG)
	adb -s $(WATCH) shell pm clear $(WEAR_PKG)
	adb -s $(WATCH) shell pm revoke $(WEAR_PKG) android.permission.POST_NOTIFICATIONS
	adb -s $(WATCH) shell cmd deviceidle whitelist -$(WEAR_PKG)
	gradlew.bat :app:assembleDebug :wear:assembleDebug
	adb -s $(PHONE) install -r $(PHONE_APK)
	adb -s $(WATCH) install -r $(WEAR_APK)
