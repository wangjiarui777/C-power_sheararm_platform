---
name: project-cleanup-audit
description: Audit project for redundant files, unused code, and provide cleanup recommendations
---

# Project Cleanup Audit Skill

Perform comprehensive project analysis to identify redundant files, unused code, and provide actionable cleanup recommendations.

## When to Use

- User asks to "检查项目中有哪些冗杂无用文件代码"
- User wants to "列出这些并提供清理建议"
- Need to identify dead code, unused dependencies, or unnecessary files
- Project maintenance and optimization

## Workflow Steps

### Phase 1: Project Structure Analysis
1. **List top-level structure**: `ls -la` to see project root
2. **Map directory tree**: `find . -maxdepth 3 -type d | head -80` to understand layout
3. **Use subagents for parallel exploration**:
   - Explore backend Java structure
   - Explore Vue frontend structure
   - Explore Python ML services
   - Explore config and doc files

### Phase 2: File Inventory
4. **Find all files**: `find . -type f -not -path './.git/*' -not -path './node_modules/*'`
5. **Check git tracked files**: `git ls-files` to see what's version controlled
6. **Identify untracked files**: `git ls-files --others --exclude-standard`

### Phase 3: Dependency Analysis
7. **Check Python imports**: Grep for `import` statements across Python files
8. **Check JavaScript imports**: Grep for `import` and `require` statements
9. **Identify unused modules**: Compare imports with actual usage

### Phase 4: Redundancy Detection
10. **Find duplicate scripts**: Compare similar files (e.g., `04.3_diagnose_unlabeled_target.py` vs `04.4_diagnose_unlabeled_target.py`)
11. **Check for TODO/FIXME**: `grep -rn "TODO\|FIXME" --include="*.vue" --include="*.js"`
12. **Identify empty or stub files**: Files with minimal content

### Phase 5: Size Analysis
13. **Check large directories**: `du -sh */` to find space consumers
14. **Identify build artifacts**: `target/`, `node_modules/`, `__pycache__/`
15. **Check for binary files**: `.pth`, `.mat`, `.jar`, `.class` files

### Phase 6: Code Quality Indicators
16. **Check for commented-out code**: Large blocks of commented code
17. **Identify deprecated patterns**: Old API usage, deprecated functions
18. **Check for hardcoded values**: Paths, credentials, magic numbers

## Analysis Dimensions

### File Types to Audit
- **Python files**: Check for unused imports, dead functions
- **Vue/JS files**: Check for unused components, dead code
- **Java files**: Check for unused classes, methods
- **Config files**: Check for outdated settings
- **Documentation**: Check for outdated or duplicate docs

### Common Redundancies in RuoYi-Vue Projects
- Multiple inference service variants (e.g., `inference_service.py` vs `enhanced_inference_service.py`)
- Test scripts that are no longer needed
- Build artifacts committed to git
- Duplicate configuration files
- Unused Vue components

## Output Format

Provide a structured report with:
1. **Summary**: Total files analyzed, estimated cleanup potential
2. **Category breakdown**:
   - Unused files (with paths)
   - Redundant code (with file:line references)
   - Build artifacts (with sizes)
   - Configuration issues
3. **Priority recommendations**:
   - High impact (safe to remove)
   - Medium impact (review needed)
   - Low impact (optional cleanup)
4. **Action plan**: Step-by-step cleanup instructions

## Stopping Conditions

- All major directories explored
- Import dependencies mapped
- Redundancies identified and categorized
- Recommendations provided with confidence levels

## Error Handling

- If project structure is unclear: Ask user for clarification
- If files are locked/in use: Note which files cannot be deleted
- If dependencies are unclear: Mark for manual review

## Example Usage

```
User: 检查这个项目中有哪些冗杂无用文件代码，列出这些并提供清理建议
Assistant: I'll perform a comprehensive audit of the project to identify redundant files and unused code. Let me start by exploring the project structure.
```

## Related Skills

- `github-packaging`: Clean up before packaging
- `inference-service-check`: Verify inference service components
