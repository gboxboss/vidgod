# Device test results: 25/25 passed

- ✅ `AiFeaturesTest_autoCaptionsTranscribeSpeech` — PASS
- ✅ `AiFeaturesTest_textToSpeechWritesAudio` — PASS
- ✅ `CatalogTest_everyFontLoadsAndRenders` — PASS
- ✅ `CatalogTest_everyStickerRenders` — PASS
- ✅ `CatalogTest_everyTextStyleRenders` — PASS
- ✅ `ExportTest_allEffectsRender` — PASS
- ✅ `ExportTest_allFiltersRender` — PASS
- ✅ `ExportTest_allTransitionsRender` — PASS
- ✅ `ExportTest_cutoutFeatures` — PASS
- ✅ `ExportTest_hevcSourceAndHevcOutput` — PASS
- ✅ `ExportTest_landscape60fpsAt30And60` — PASS
- ✅ `ExportTest_photoSlideshowHasSound` — PASS
- ✅ `ExportTest_pictureInPicture30` — PASS
- ✅ `ExportTest_pictureInPicture60` — PASS
- ✅ `ExportTest_reverseRotatedClip` — PASS
- ✅ `ExportTest_richProject` — PASS
- ✅ `ExportTest_rotatedPhoneClip` — PASS
- ✅ `ExportTest_singlePortraitClip` — PASS
- ✅ `ExportTest_transitionBlendsFromPreviousClip` — PASS
- ✅ `PreviewTest_liveEditsWithMusicWhilePaused` — PASS
- ✅ `PreviewTest_pictureInPictureLifecycle` — PASS
- ✅ `PreviewTest_singleClipPlaysAndSeeks` — PASS
- ✅ `PreviewTest_speedCurveDurationMatchesTimeline` — PASS
- ✅ `UiFlowTest_editorFlow` — PASS
- ✅ `UiFlowTest_newProjectThroughPhotoPicker` — PASS

## Exported files (ffprobe / loudness)
```
== export_all_effects.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=602
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=480|height=852|avg_frame_rate=30/1|nb_frames=420
  format|duration=14.000000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== export_all_filters.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=353
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=480|height=852|avg_frame_rate=30/1|nb_frames=246
  format|duration=8.200000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== export_all_transitions.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=852
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=480|height=852|avg_frame_rate=30/1|nb_frames=594
  format|duration=19.800000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== export_cutouts.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=194
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=135
  format|duration=4.500000
  mean_volume: -24.2 dB
  max_volume: -19.6 dB
== export_dissolve.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=94
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=480|height=852|avg_frame_rate=30/1|nb_frames=66
  format|duration=2.200000
  mean_volume: -26.9 dB
  max_volume: -20.7 dB
== export_hevc_output.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=86
  stream|codec_name=hevc|profile=Main|codec_type=video|width=270|height=480|avg_frame_rate=30/1|nb_frames=60
  format|duration=2.000000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== export_hevc_source.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=86
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=60
  format|duration=2.000000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== export_landscape_30.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=48000|channels=2|avg_frame_rate=0/0|nb_frames=141
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=91
  format|duration=3.033333
  mean_volume: -24.2 dB
  max_volume: -19.9 dB
== export_landscape_60.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=48000|channels=2|avg_frame_rate=0/0|nb_frames=141
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=60/1|nb_frames=181
  format|duration=3.016667
  mean_volume: -24.2 dB
  max_volume: -19.9 dB
== export_pip_30.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=173
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=121
  format|duration=4.033333
  mean_volume: -19.8 dB
  max_volume: -6.2 dB
== export_pip_60.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=173
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=60/1|nb_frames=241
  format|duration=4.016667
  mean_volume: -19.8 dB
  max_volume: -6.2 dB
== export_rich.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=479
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=335
  format|duration=11.166667
  mean_volume: -21.2 dB
  max_volume: -4.8 dB
== export_rotated.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=48000|channels=2|avg_frame_rate=0/0|nb_frames=141
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=91
  format|duration=3.033333
  mean_volume: -24.1 dB
  max_volume: -20.3 dB
== export_single.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=173
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=121
  format|duration=4.033333
  mean_volume: -24.2 dB
  max_volume: -20.7 dB
== export_slideshow.mp4
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=44100|channels=2|avg_frame_rate=0/0|nb_frames=258
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=1080|height=1920|avg_frame_rate=30/1|nb_frames=180
  format|duration=6.000000
  mean_volume: -91.0 dB
  max_volume: -91.0 dB
== reverse_rotated.mp4
  stream|codec_name=h264|profile=Constrained Baseline|codec_type=video|width=720|height=1280|avg_frame_rate=30/1|nb_frames=90
  stream|codec_name=aac|profile=LC|codec_type=audio|sample_rate=48000|channels=2|avg_frame_rate=0/0|nb_frames=141
  format|duration=3.008000
  mean_volume: -24.2 dB
  max_volume: -20.3 dB

```

## Release APK smoke test
```
Performing Streamed Install
Success
media id of smoke.mp4: 1000000041
media id of speech.mp4: 1000000042
--- home
PASS home
--- open_video
PASS open_video
--- play
time 00:00 / 00:04 -> 00:02 / 00:04
PASS play
--- add_text
PASS add_text
--- play_after_text
time 00:02 / 00:04 -> 00:04 / 00:04
PASS play_after_text
--- export
exported: VidGod_20261002_191908.mp4
PASS export
--- reopen_project
reopened: 00:00 / 00:04
PASS reopen_project
--- auto_captions
captions: ['CC  the low well they', 'CC  seem a cop show']
PASS auto_captions
--- text_to_speech
PASS text_to_speech
--- back_home
PASS back_home

RELEASE SMOKE: PASS

```

## Test log
```
tts ok=true size=61484
captions from text to speech: "hello world this is a caption test" (4 of 4 words) hello@30ms, world@420ms, this@1050ms, is@1290ms, a@1440ms, caption@1500ms, test@1980ms
captions from espeak: "the low well they seem a cop show" (0 of 4 words) the@0ms, low@182ms, well@390ms, they@1350ms, seem@1500ms, a@1712ms, cop@1800ms, show@2130ms
EXPORT export_all_filters: VideoInfo(videoMime=video/avc, codedWidth=480, codedHeight=852, rotation=0, durationUs=8200000, videoFrames=246, fps=30.000002448979792, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=8137142, fileBytes=3587439) (took 214356 ms, gallery=content://media/external_primary/video/media/1000000019)
EXPORT export_dissolve: VideoInfo(videoMime=video/avc, codedWidth=480, codedHeight=852, rotation=0, durationUs=2200000, videoFrames=66, fps=30.000009230772072, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=2123174, fileBytes=1237040) (took 10762 ms, gallery=content://media/external_primary/video/media/1000000020)
dissolve lumas A=122.0 mid=124.5 B=128.3
EXPORT export_all_transitions: VideoInfo(videoMime=video/avc, codedWidth=480, codedHeight=852, rotation=0, durationUs=19800000, videoFrames=594, fps=30.000001011804418, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=19723900, fileBytes=8322082) (took 112538 ms, gallery=content://media/external_primary/video/media/1000000021)
EXPORT export_hevc_source: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=2000000, videoFrames=60, fps=30.000010169494974, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=1937414, fileBytes=3507886) (took 18961 ms, gallery=content://media/external_primary/video/media/1000000022)
EXPORT export_hevc_output: VideoInfo(videoMime=video/hevc, codedWidth=270, codedHeight=480, rotation=0, durationUs=2000000, videoFrames=60, fps=30.000010169494974, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=1937414, fileBytes=1056795) (took 16196 ms, gallery=content://media/external_primary/video/media/1000000023)
EXPORT export_single: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=4033000, videoFrames=121, fps=30.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=3957551, fileBytes=6674842) (took 31662 ms, gallery=content://media/external_primary/video/media/1000000024)
rotated source: MediaSource(uri=file:///data/user/0/com.vidgod.editor.debug/files/test-media/rotated.mp4, kind=VIDEO, name=rotated.mp4, durationUs=3021000, width=720, height=1280, hasAudio=true, frameRate=30.0, isHdr=false)
EXPORT export_rotated: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=3033000, videoFrames=91, fps=30.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=48000, audioDurationUs=2953333, fileBytes=1354223) (took 25235 ms, gallery=content://media/external_primary/video/media/1000000025)
pip overlays: [layer=0 start=500000 dur=3000000, layer=1 start=1000000 dur=3021000]
EXPORT export_pip_30: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=4033000, videoFrames=121, fps=30.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=3957551, fileBytes=6622411) (took 58542 ms, gallery=content://media/external_primary/video/media/1000000026)
EXPORT export_pip_60: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=4017000, videoFrames=241, fps=60.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=3957551, fileBytes=10770375) (took 93071 ms, gallery=content://media/external_primary/video/media/1000000027)
EXPORT export_slideshow: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=6000000, videoFrames=180, fps=30.00000335195568, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=5931247, fileBytes=8009308) (took 48899 ms, gallery=content://media/external_primary/video/media/1000000028)
EXPORT export_landscape_30: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=3033000, videoFrames=91, fps=30.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=48000, audioDurationUs=2953333, fileBytes=4955908) (took 25261 ms, gallery=content://media/external_primary/video/media/1000000029)
EXPORT export_landscape_60: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=3017000, videoFrames=181, fps=60.0, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=48000, audioDurationUs=2953333, fileBytes=9569786) (took 46006 ms, gallery=content://media/external_primary/video/media/1000000030)
EXPORT export_cutouts: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=4505000, videoFrames=135, fps=30.00000447761261, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=4445170, fileBytes=5833191) (took 41903 ms, gallery=content://media/external_primary/video/media/1000000031)
rich: clip durations [4023000, 1510500, 5620448] total=11153948
EXPORT export_rich: VideoInfo(videoMime=video/avc, codedWidth=1080, codedHeight=1920, rotation=0, durationUs=11167000, videoFrames=335, fps=30.00000089820362, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=11062857, fileBytes=16418048) (took 149684 ms, gallery=content://media/external_primary/video/media/1000000032)
REVERSE: VideoInfo(videoMime=video/avc, codedWidth=720, codedHeight=1280, rotation=0, durationUs=3008000, videoFrames=90, fps=30.00000674157455, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=48000, audioDurationUs=2986666, fileBytes=267833)
REVERSE probe: MediaSource(uri=file:///data/user/0/com.vidgod.editor.debug/cache/reverse-test/reverse_3a316cb5-b5f_0_3021000.mp4, kind=VIDEO, name=reverse_3a316cb5-b5f_0_3021000.mp4, durationUs=3008000, width=720, height=1280, hasAudio=true, frameRate=30.0, isHdr=false)
EXPORT export_all_effects: VideoInfo(videoMime=video/avc, codedWidth=480, codedHeight=852, rotation=0, durationUs=14000000, videoFrames=420, fps=30.000001431980976, audioMime=audio/mp4a-latm, audioChannels=2, audioSampleRate=44100, audioDurationUs=13918911, fileBytes=6506337) (took 41068 ms, gallery=content://media/external_primary/video/media/1000000033)
FRAME preview_pip_start mean=128.3 sd=65.6 fresh=true frames=1
FRAME preview_pip_scaled mean=128.3 sd=65.6 fresh=true frames=2
FRAME preview_pip_with_text mean=129.8 sd=65.0 fresh=true frames=3
pip reached end: 4023000 / 4023000
played 1500ms: 4023000 -> 1503000, error=null
FRAME preview_pip_replay mean=129.9 sd=64.9 fresh=true frames=22
played 1500ms: 0 -> 1257000, error=null
FRAME preview_pip_removed mean=129.8 sd=65.1 fresh=true frames=33
speed curve: timeline 3108 ms, played 4271 ms, end position 3108442
FRAME preview_single_start mean=128.3 sd=65.6 fresh=true frames=1
played 2000ms: 0 -> 2000000, error=null
FRAME preview_single_seek3s mean=128.5 sd=65.6 fresh=false frames=17
FRAME preview_music_edited mean=128.6 sd=65.6 fresh=true frames=2
played 1500ms: 0 -> 1490000, error=null
PASS ui_01_open_editor (5005ms)
play before=00:00 / 00:10 after=00:02 / 00:10
PASS ui_02_play_pause (4547ms)
after background: 00:02 / 00:10 -> 00:02 / 00:10
preview after background: mean=128.2 sd=63.4
PASS ui_03_background_pauses (6816ms)
scrub before=00:02 / 00:10 after=00:04 / 00:10
PASS ui_04_scrub_timeline (2665ms)
PASS ui_05_tap_clip_selects (3066ms)
after back_to_start: 00:00 / 00:10
PASS ui_06_back_to_start (5113ms)
PASS ui_07_select_clip (999ms)
trim_start: clip lengths [00:04.0, 00:03.0, 00:03.0, 00:00.0] -> [00:02.6, 00:03.0, 00:03.0, 00:00.0], total 00:10 -> 00:08
PASS ui_08_trim_clip_start (6673ms)
trim_end: clip lengths [00:04.0, 00:03.0, 00:03.0, 00:03.2] -> [00:02.6, 00:03.0, 00:03.0, 00:03.2], total 00:10 -> 00:08
PASS ui_09_trim_clip_end (13552ms)
pinch zoom: clip width 391.0 -> 1386.0 -> 391.0
PASS ui_10_pinch_zoom (4479ms)
PASS ui_11_clip_Speed (3155ms)
PASS ui_12_clip_Volume (3113ms)
PASS ui_13_clip_Animation (3225ms)
PASS ui_14_clip_Filters (3215ms)
PASS ui_15_clip_Adjust (3319ms)
PASS ui_16_clip_Effects (3327ms)
PASS ui_17_clip_Transform (3265ms)
PASS ui_18_clip_Opacity (3235ms)
PASS ui_19_clip_Mask (3322ms)
PASS ui_20_clip_Chroma_key (3231ms)
PASS ui_21_clip_Voice_FX (3245ms)
PASS ui_22_clip_Transition (3180ms)
PASS ui_23_split (1687ms)
PASS ui_24_deselect (962ms)
PASS ui_25_add_text (7575ms)
PASS ui_26_deselect_text (710ms)
PASS ui_27_add_sticker (4077ms)
PASS ui_28_deselect_sticker (1009ms)
PASS ui_29_add_sound_effect (3699ms)
PASS ui_30_add_music_from_files (18781ms)
PASS ui_31_panel_Styles (11712ms)
PASS ui_32_panel_Audio (11690ms)
PASS ui_33_panel_Effects (11705ms)
PASS ui_34_panel_Filters (11689ms)
PASS ui_35_panel_Captions (11771ms)
PASS ui_36_panel_Ratio (11789ms)
PASS ui_37_panel_Canvas (11805ms)
PASS ui_38_panel_Reorder (11856ms)
1:1 canvas: 200.0 x 200.0
1:1 preview: centre of brightness at x=0.51
PASS ui_39_ratio_1_1 (10642ms)
PASS ui_40_undo_redo (6098ms)
full screen lower half: mean=126.9 sd=93.3
PASS ui_41_fullscreen (6410ms)
play before=00:03 / 00:10 after=00:06 / 00:10
PASS ui_42_play_after_edits (5527ms)
PASS ui_43_export (95073ms)
PASS ui_44_back_home (3545ms)
reopened project: 00:00 / 00:10
PASS ui_45_reopen_project (6555ms)
PASS ui_46_duplicate_rename_delete_project (2557ms)
PASS picker_01_home (1078ms)
PASS picker_02_open_picker (5842ms)
picker items: [Video taken on Oct 2, 2026, 7:17:45 PM with duration 00:04, Photo taken on Oct 2, 2026, 7:17:45 PM, Video taken on Oct 2, 2026, 7:17:26 PM with duration 00:10, Video taken on Oct 2, 2026, 7:09:26 PM with duration 00:14, Video taken on Oct 2, 2026, 7:09:14 PM with duration 00:11, Video taken on Oct 2, 2026, 7:06:42 PM with duration 00:04, Video taken on Oct 2, 2026, 7:05:58 PM with duration 00:03, Video taken on Oct 2, 2026, 7:05:11 PM with duration 00:03, Video taken on Oct 2, 2026, 7:03:54 PM with duration 00:06, Video taken on Oct 2, 2026, 7:02:20 PM with duration 00:04, Video taken on Oct 2, 2026, 7:01:19 PM with duration 00:04, Video taken on Oct 2, 2026, 7:01:16 PM with duration 00:03]
PASS picker_03_choose_media (1444ms)
preview of the new project: mean=128.4 sd=63.8
PASS picker_04_editor_opened (5836ms)
play before=00:00 / 00:07 after=00:02 / 00:07
PASS picker_05_play (3832ms)

```
