# Editable NewPipeExtractor source module

Upstream: TeamNewPipe/NewPipeExtractor **v0.26.5**. GPL-3.0-or-later; upstream headers retained.

Source archive: https://jitpack.io/com/github/TeamNewPipe/NewPipeExtractor/v0.26.5/NewPipeExtractor-v0.26.5-sources.jar

Archive SHA-256: `693c1e23cfaae77af3693af2441261ab89d55a89b11a35d36ecdec03dbb4e7c8`

All Java sources were imported from this published source archive, including its generated protobuf and timeago sources. The proto schemas are retained under `upstream-proto/` for reference; generated Java sources are compiled directly, so this simplified consumer module does not regenerate them. `UPSTREAM.pom` records the original dependency versions.

Only upstream Java source modification: `utils/Utils.java`, switching both URL encode/decode UTF-8 helpers from `(String, Charset)` to `(String, String)`. See `android-url-codec.patch`. Other Java source files, including `YoutubeStreamExtractor.java`, are unchanged from this exact source archive.

This is an editable local fork, not an official upstream release. Do not add the original NewPipeExtractor binary dependency alongside it: that would duplicate its classes.

The source implements the same extractor family as the user's pasted YoutubeStreamExtractor class; replacing that single class would not fix a missing java.net.URLDecoder overload in Utils.

Build uses a small Java-library module (Java 11 bytecode). Android D8/core-library desugaring remains configured in the consuming app. Changing Java language level alone does not backport unsupported platform APIs.

Regression tests verify URL form semantics, Unicode, YouTube link routing without network, and the actual compiled Utils call descriptors against the installed Android API 28 SDK stubs. These are not tests running on an Android phone. Other Android API compatibility issues are not ruled out by these targeted tests.
