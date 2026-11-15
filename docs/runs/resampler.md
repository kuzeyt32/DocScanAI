# H43 - resampler vs corner ACCURACY

`locked` = all four channels clear the 0.30 gate. `correct` = model quad matches the
page (IoU >= 0.5). `confident+wrong` = locked but IoU < 0.2, i.e. the model
latched onto something that is not the page. Only a resampler that raises `correct`
without raising `confident+wrong` is a real fix.

| resampler | locked | correct | confident+wrong | mean IoU |
|---|---|---|---|---|
| LINEAR squash (shipped) | 30 | **23** | 2 | 0.216 |
| CUBIC squash | 33 | **25** | 3 | 0.256 |
| LETTERBOX cubic | 61 | **42** | 9 | 91525.624 |
| LETTERBOX linear | 55 | **38** | 8 | 0.314 |

## Per-photo change vs the shipped resampler

* **CUBIC squash**: +5 correct, -0 correct
  * gained: `20261024_185359.jpg`, `20261024_185534.jpg`, `20261024_185610.jpg`, `20261024_185758.jpg`, `20261025_131201.jpg`
* **LETTERBOX cubic**: +22 correct, -3 correct
  * gained: `20261020_195404.jpg`, `20261020_200014.jpg`, `20261024_185245.jpg`, `20261024_185341.jpg`, `20261024_185345.jpg`, `20261024_185359.jpg`, `20261024_185404.jpg`, `20261024_185449.jpg`, `20261024_185509.jpg`, `20261024_185600.jpg`, `20261024_185610.jpg`, `20261024_185736.jpg`
  * lost: `20261024_185532.jpg`, `20261024_185543.jpg`, `20261024_185745.jpg`
* **LETTERBOX linear**: +16 correct, -3 correct
  * gained: `20261020_195404.jpg`, `20261020_200014.jpg`, `20261024_185245.jpg`, `20261024_185341.jpg`, `20261024_185345.jpg`, `20261024_185359.jpg`, `20261024_185404.jpg`, `20261024_185449.jpg`, `20261024_185600.jpg`, `20261024_185610.jpg`, `20261024_185758.jpg`, `20261024_185809.jpg`
  * lost: `20261024_185532.jpg`, `20261024_185543.jpg`, `20261024_185745.jpg`
