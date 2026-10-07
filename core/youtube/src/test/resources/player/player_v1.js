// Fixture player js — mimics an EJS-preprocessed base.js. Function names match
// player_configs_v1.json so the solver can invoke them by name.
var pianostreamSig = function (a) {
    return a.split("").reverse().join("");
};
var pianostreamN = function (a) {
    return a.replace(/x/g, "y");
};
