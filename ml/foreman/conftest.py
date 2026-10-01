"""Puts ml/foreman on sys.path so `classifier` and `planner` import as packages under pytest."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
