# Volumind Remote

אפליקציית Android לשליטה מאובטחת ב־Volumind AI מכל מקום ולצפייה בהתקדמות המודל ב־Fusion 360.

## מה כבר קיים

- צימוד למחשב באמצעות קוד חד־פעמי בן 6 ספרות.
- צ׳אט חי ושליחת פקודות ל־Fusion.
- תוכנית בנייה והתקדמות שלב־אחר־שלב.
- מסך **מודל חי** שמתעדכן בצילום חדש אחרי כל שלב.
- כפתור עצירת חירום.
- Relay עם חיבורים יוצאים בלבד; אין חשיפה ישירה של Ollama או Fusion.
- חסימת HTTP לא מוצפן באפליקציית Android.

## מבנה

- `app/` — אפליקציית Android ב־Kotlin ו־Jetpack Compose.
- `relay/` — שרת WebSocket קטן לפריסה מאחורי HTTPS/WSS.
- `bridge/` — מחבר Windows שיוצר חיבור יוצא ומעביר פקודות לתוסף Fusion דרך localhost.
- `fusion-addin/VolumindAI/` — תוסף Fusion 360 גרסה 3.0 עם API מקומי וחיבור מלא לטלפון.

## הפעלה לפיתוח

1. פרוס את `relay/` בשירות שתומך ב־Node ו־WebSocket והפעל TLS.
2. החלף את `RELAY_URL` ב־`app/build.gradle.kts` לכתובת `wss://.../ws`.
3. פתח את תיקיית המאגר ב־Android Studio ובנה את האפליקציה, או הרץ `gradlew.bat assembleDebug`.
4. העתק את `fusion-addin/VolumindAI` אל `%APPDATA%\Autodesk\Autodesk Fusion 360\API\AddIns` והפעל אותו מתוך Fusion.
5. מתוך תיקיית `bridge` הרץ `run_bridge.ps1 -RelayUrl "wss://YOUR-SERVER/ws"`.
6. הזן באפליקציה את קוד הצימוד בן שש הספרות שמופיע בחלון PowerShell.

## החיבור לתוסף Fusion

תוסף Fusion המצורף מפעיל API מקומי ב־`127.0.0.1:8765`. הוא מקבל פקודות, תשובות לשאלון ועצירה, ומחזיר תשובות צ׳אט, תוכנית בנייה, סטטוס שלבים וצילום viewport אמיתי אחרי כל שלב.

## מה עדיין דורש הגדרה לפני שימוש מכל מקום

- כתובת שרת ציבורית עם HTTPS/WSS עבור `relay/`.
- החלפת `RELAY_URL` ב־`app/build.gradle.kts` בכתובת השרת שנפרס.
- הגדרת `VOLUMIND_RELAY_URL=wss://.../ws` במחשב שמריץ את Fusion.

## אבטחה

אין לפרוס את ה־relay ללא TLS. אין לחשוף את Ollama (`11434`) או את API ה־Fusion המקומי לאינטרנט. קוד הצימוד פג אחרי עשר דקות ונמחק לאחר שימוש.
