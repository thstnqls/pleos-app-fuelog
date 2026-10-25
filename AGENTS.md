# fuelog 작업 지침

Pleos Connect 정책 준수, 출시 준비 또는 차량 앱 설계 제약 검토를 요청받으면
`.agents/skills/pleos-policy-review/SKILL.md`를 읽고 해당 범위에 적용한다.
전용 검토 에이전트 정의는 `.codex/agents/pleos-policy-reviewer.toml`이다.
일반적인 코드 수정마다 전체 정책 감사를 수행할 필요는 없다.

호출 예:

- `$pleos-policy-review 현재 앱의 정책상 걸리는 지점과 수정안을 정리해줘.`
- `pleos_policy_reviewer 에이전트에게 이번 변경의 Pleos 정책 검토를 맡겨줘.`

정책 검토는 보고서와 수정안 도출까지를 기본 범위로 한다. 구현 수정도 요청되면
해당 변경을 수행한다. 기존 미커밋 변경은 보존한다.
