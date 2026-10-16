# 项目工作约定

- 日常界面迭代只做必要的源码修改、编译检查和运行预览。
- 只有用户明确要求打包时，才生成或更新分发产物。不要自动运行 `packageSingleExe`、`packagePortableZip`、`packageMsi`、`packageExe`、`createDistributable` 等打包任务，也不要自动重建 EXE、MSI 或 ZIP。
