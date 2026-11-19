# V26 응원가 (Android)

PC용 V26Cheer.exe와 같은 방식으로 동작합니다.
화면 캡처(MediaProjection) → 한글 OCR(ML Kit) → 등록한 선수 이름 매칭 → 응원가 재생.

## APK 만드는 법
### A. Android Studio (추천)
1. 이 폴더를 Android Studio로 열기 → Gradle Sync
2. Build > Build APK(s) → app/build/outputs/apk/debug/app-debug.apk

### B. 설치 없이 GitHub Actions
1. 이 폴더를 새 GitHub 저장소에 올리기 (push)
2. Actions 탭 > Build APK 실행 > Artifacts의 V26Cheer-apk 다운로드

## 사용법
1. 선수 이름 입력 + 응원가 파일(mp3 등) 선택해서 추가
2. 타자 이름이 표시되는 영역을 슬라이더로 지정
3. 시작 → 화면 캡처 허용 → 컴프야v26 실행
4. 알림창의 '인식: …' 문구로 이름이 제대로 읽히는지 확인하며 영역 조정
