# Slim OpenCV for the face match

`app/src/main/jniLibs/arm64-v8a/libopencv_java4.so` is OpenCV 4.14.0 built from the
release source with only the modules the face match uses (core, imgproc, dnn,
objdetect and the Java bindings), 19.3 MB against the official package's 24.7 MB.
Everything else matches the official Android build so the SFace vectors are
identical: NDK r27c (27.2), API 21, c++_shared, -O3, NEON+FP16 baseline with
NEON_DOTPROD/FP16/BF16 dispatch, TBB, ITT, carotene and KleidiCV HALs, static
protobuf. The Java classes come from the official package (`opencv-4.14.0-classes.jar`).
`configure.log` is the CMake summary of this build.

To rebuild (about 25 minutes on a Mac): unpack the OpenCV 4.14.0 source and NDK r27c,
download `kleidicv-26.03.tar.gz` from gitlab.arm.com into a folder and point
`OPENCV_KLEIDICV_URL=file://<folder>/` at it, then configure with the Android
toolchain file and `-DBUILD_LIST=core,imgproc,dnn,objdetect,java,java_bindings_generator
-DBUILD_JAVA=ON -DBUILD_FAT_JAVA_LIB=ON -DANDROID_PROJECTS_BUILD_TYPE=GRADLE -DWITH_TBB=ON
-DBUILD_TBB=ON -DWITH_ITT=ON -DWITH_KLEIDICV=ON -DWITH_CAROTENE=ON -DBUILD_PROTOBUF=ON
-DCPU_BASELINE=NEON,FP16 -DCPU_DISPATCH=NEON_FP16,NEON_BF16,NEON_DOTPROD`, build the
`opencv_java` target with Ninja and `llvm-strip --strip-unneeded` the result. Check it
with the debug face probe (`FaceProbeActivity`): the vectors must match the official
library bit for bit.
