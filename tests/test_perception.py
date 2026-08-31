from drowsy.perception import eye_aspect_ratio, mouth_aspect_ratio, Point2D, MockPerceptionEngine, LandmarkPerceptionEngine
import numpy as np

def test_ear_open_vs_closed():
    open_eye = [Point2D(0,0),Point2D(1,1),Point2D(2,1),Point2D(4,0),Point2D(2,-1),Point2D(1,-1)]
    closed_eye = [Point2D(0,0),Point2D(1,0.1),Point2D(2,0.1),Point2D(4,0),Point2D(2,-0.1),Point2D(1,-0.1)]
    assert eye_aspect_ratio(open_eye) > eye_aspect_ratio(closed_eye)
    assert eye_aspect_ratio(closed_eye) < 0.2

def test_mar_yawn():
    closed = [Point2D(0,0),Point2D(4,0),Point2D(2,0.2),Point2D(2,-0.2)]
    yawn = [Point2D(0,0),Point2D(4,0),Point2D(2,1.5),Point2D(2,-1.5)]
    assert mouth_aspect_ratio(yawn) > mouth_aspect_ratio(closed)
    assert mouth_aspect_ratio(yawn) > 0.5

def test_mock_engine():
    eng = MockPerceptionEngine()
    frame = eng.process(np.zeros((480,640,3),dtype=np.uint8), 0)
    assert not frame.face_present
