---
name: github-packaging
description: Package and upload project to GitHub with proper checks and cleanup
---

# GitHub Packaging Skill

Package a project for GitHub upload, performing necessary checks and cleanup steps before committing and pushing.

## When to Use

- User asks to "打包上传到github" or "打包提交到github"
- User wants to publish the project to a remote repository
- Need to prepare project for version control sharing

## Workflow Steps

### Phase 1: Pre-flight Checks
1. **Check git status**: `git status --short` to see current state
2. **Check existing remotes**: `git remote -v` to verify remote configuration
3. **Review recent commits**: `git log --oneline -5` to understand commit history
4. **Check gitignore**: Read `.gitignore` to ensure proper exclusions

### Phase 2: Size and Dependency Analysis
5. **Find large files**: `find . -type f -size +10M -not -path "./.git/*" -not -path "./node_modules/*"`
6. **Check node_modules**: Verify if `node_modules` exists and is gitignored
7. **Check cached files**: `git ls-files --cached .venv/` and `git ls-files --cached '*.pth'`
8. **Check directory sizes**: `du -sh . --exclude=.git --exclude=node_modules`

### Phase 3: Remote Configuration
9. **Set remote URL**: Configure GitHub remote if needed
   - Pattern: `git remote set-url origin https://github.com/<username>/<repo>.git`
   - Verify with: `git remote -v`

### Phase 4: Staging and Committing
10. **Stage all changes**: `git add -A && git status --short`
11. **Create commit**: `git commit -m "feat: <descriptive message>"`
    - Use conventional commit format
    - Include relevant feature/fix description

### Phase 5: Push to Remote
12. **Push to GitHub**: `git push -u origin main`
    - Use `-u` flag to set upstream tracking
    - Ensure authentication is configured

## Common Patterns

### For RuoYi-Vue Projects
- Check for `.pth` model files (should be gitignored)
- Verify `node_modules` is excluded
- Check `target/` directories for Java builds
- Ensure `__pycache__/` is excluded

### Commit Message Patterns
- `feat: 添加新功能描述`
- `fix: 修复问题描述`
- `docs: 更新文档`
- `chore: 维护任务`

## Stopping Conditions

- Git remote is properly configured
- All changes are staged and committed
- Push to remote succeeds without errors

## Error Handling

- If push fails due to authentication: Guide user to configure git credentials
- If large files found: Advise adding to `.gitignore` or using Git LFS
- If remote not configured: Help set up remote repository

## Example Usage

```
User: 把这个项目打包上传到github
Assistant: I'll help you package and upload this project to GitHub. Let me start with the pre-flight checks.
```

## Related Skills

- `project-cleanup-audit`: Clean up redundant files before packaging
- `inference-service-check`: Verify inference service components
