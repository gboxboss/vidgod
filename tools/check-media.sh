#!/usr/bin/env bash
# Prints the streams and the audio loudness of every exported video in a folder
# (catches silent exports, wrong codecs, sizes or frame rates).
dir=$1
for f in "$dir"/*.mp4; do
  [ -f "$f" ] || continue
  echo "== $(basename "$f")"
  ffprobe -v error -show_entries format=duration:stream=codec_type,codec_name,profile,width,height,avg_frame_rate,sample_rate,channels,nb_frames \
    -of compact "$f" | sed 's/^/  /'
  if ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$f" | grep -q .; then
    ffmpeg -hide_banner -nostats -i "$f" -map 0:a:0 -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume" | sed 's/^.*\] /  /'
  else
    echo "  NO AUDIO STREAM"
  fi
done
