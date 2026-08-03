Die Idee hier ist, verschiedene Module zu schreiben, die jedes Plugin als tatsächlich vollständige Module einbinden kann, ohne den Code duplizieren zu müssen.
Diese Module sollten nicht gevendored sondern möglichst über Maven eingebunden werden, sodass der Sourcecode der Module die SSOT ist.

Die Module sollten außerdem je durch einen Wrapper so gewrappt werden können, dass sie jederzeit als standalone Plugin geshippt werden könnten. Hierfür sollte ein Standard-Wrapper bereitgestellt werden.

Folgende Plugins sollten die Module implementieren: 
- SMPCore
- TheHungerGames