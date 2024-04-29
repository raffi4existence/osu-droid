package com.rian.difficultycalculator.calculator;

import com.rian.difficultycalculator.attributes.DifficultyAttributes;
import com.rian.difficultycalculator.attributes.PerformanceAttributes;
import com.rian.difficultycalculator.math.MathUtils;

import ru.nsu.ccfit.zuev.osu.game.mods.GameMod;

/**
 * A performance calculator for calculating performance points.
 */
public class PerformanceCalculator {
    public static final double finalMultiplier = 1.14;

    /**
     * The difficulty attributes being calculated.
     */
    public final DifficultyAttributes difficultyAttributes;

    private int scoreMaxCombo;
    private int countGreat;
    private int countOk;
    private int countMeh;
    private int countMiss;
    private double effectiveMissCount;

    public PerformanceCalculator(DifficultyAttributes attributes) {
        this.difficultyAttributes = attributes;

        processParameters(null);
    }

    /**
     * Calculates the performance value of the difficulty attributes assuming an SS score.
     *
     * @return The performance attributes for the beatmap assuming an SS score.
     */
    public PerformanceAttributes calculate() {
        return createPerformanceAttributes();
    }

    /**
     * Calculates the performance value of the difficulty attributes with the specified parameters.
     *
     * @param parameters The parameters to create the attributes for.
     * @return The performance attributes for the beatmap relating to the parameters.
     */
    public PerformanceAttributes calculate(PerformanceCalculationParameters parameters) {
        processParameters(parameters);

        return createPerformanceAttributes();
    }

    /**
     * Creates the performance attributes of the difficulty attributes.
     *
     * @return The performance attributes for the beatmap relating to the parameters.
     */
    private PerformanceAttributes createPerformanceAttributes() {
        double multiplier = finalMultiplier;

        // Debuff the pp multiplier with the no fail mod by 15%
        if (difficultyAttributes.mods.contains(GameMod.MOD_NOFAIL)) {
            multiplier *= 0.85;
        }

        // Debuff pp multiplier with the really easy mod by 25%
        if (difficultyAttributes.mods.contains(GameMod.MOD_REALLYEASY)) {
            multiplier *= 0.75;
        }

        // Buff the pp multiplier by 15% with the precise mod
        if (difficultyAttributes.mods.contains(GameMod.MOD_PRECISE)) {
            multiplier *= 1.15;
        }

        // Debuff the pp multiplier by 2.5% with the relax mod
        if (difficultyAttributes.mods.contains(GameMod.MOD_RELAX)) {
            multiplier *= 0.975;
        }

        PerformanceAttributes attributes = new PerformanceAttributes();

        attributes.effectiveMissCount = effectiveMissCount;
        attributes.aim = calculateAimValue();
        attributes.speed = calculateSpeedValue();
        attributes.accuracy = calculateAccuracyValue();
        attributes.flashlight = calculateFlashlightValue();

        attributes.total = Math.pow(
                Math.pow(attributes.aim, 1.15) +
                        Math.pow(attributes.speed, 1.15) +
                        Math.pow(attributes.accuracy, 1) +
                        Math.pow(attributes.flashlight, 1.1),
                1 / 1.1
        ) * multiplier;

        return attributes;
    }

    private void processParameters(PerformanceCalculationParameters parameters) {
        if (parameters == null) {
            resetDefaults();
            return;
        }

        scoreMaxCombo = parameters.maxCombo;
        countGreat = parameters.countGreat;
        countOk = parameters.countOk;
        countMeh = parameters.countMeh;
        countMiss = parameters.countMiss;
        effectiveMissCount = calculateEffectiveMissCount();
    }

    /**
     * Calculates the accuracy of the parameters.
     */
    private double getAccuracy() {
        return (double) (countGreat * 6 + countOk * 2 + countMeh) / (getTotalHits() * 6);
    }

    /**
     * Gets the total hits that can be done in the beatmap.
     */
    private int getTotalHits() {
        return difficultyAttributes.hitCircleCount + difficultyAttributes.sliderCount + difficultyAttributes.spinnerCount;
    }

    /**
     * Resets this calculator to its original state.
     */
    private void resetDefaults() {
        scoreMaxCombo = difficultyAttributes.maxCombo;
        countGreat = getTotalHits();
        countOk = 0;
        countMeh = 0;
        countMiss = 0;
        effectiveMissCount = 0;
    }

    private double calculateAimValue() {

        double aimValue = Math.pow(5 * Math.max(1, difficultyAttributes.aimDifficulty / 0.0675) - 4, 3) / 100000;

        // Longer maps are worth more
        double lengthBonus = 0.95 + 0.4 * Math.min(1, getTotalHits() / 2000d);
        if (getTotalHits() > 2000) {
            lengthBonus += Math.log10(getTotalHits() / 2000d) * 0.5;
        }

        aimValue *= lengthBonus;

        if (effectiveMissCount > 0) {
            // Penalize misses by assessing # of misses relative to the total # of objects. Default a 3% reduction for any # of misses.
            aimValue *= 0.97 * Math.pow(1 - Math.pow(effectiveMissCount / getTotalHits(), 0.775), effectiveMissCount);
        }

        aimValue *= getComboScalingFactor();

        // We want to give more reward for lower AR when it comes to aim and HD. This nerfs high AR and buffs lower AR.
        if (difficultyAttributes.mods.contains(GameMod.MOD_HIDDEN)) {
            aimValue *= 1 + 0.04 * (12 - difficultyAttributes.approachRate);
        }

        // We buff the aim pp value by 22% and by adding the approach rate value multiplied by 0.0025
        // For example, if the approach rate is 10.33 with double time, 10.33 * 0.0025 = 0.0387375
        // And then we add it to the aim pp value multiplier, and that would be equal to 1.2587375
        if (difficultyAttributes.mods.contains(GameMod.MOD_RELAX)) {
            aimValue *= 1.225 + (difficultyAttributes.approachRate * 0.00375);
        } 

        // We assume 15% of sliders in a map are difficult since there's no way to tell from the performance calculator.
        double estimateDifficultSliders = difficultyAttributes.sliderCount * 0.15;

        if (estimateDifficultSliders > 0) {
            double estimateSliderEndsDropped = MathUtils.clamp(Math.min(countOk + countMeh + countMiss, difficultyAttributes.maxCombo - scoreMaxCombo), 0, estimateDifficultSliders);
            double sliderNerfFactor = (1 - difficultyAttributes.aimSliderFactor) * Math.pow(1 - estimateSliderEndsDropped / estimateDifficultSliders, 3) + difficultyAttributes.aimSliderFactor;
            aimValue *= sliderNerfFactor;
        }

        // Scale the aim value with accuracy.
        aimValue *= getAccuracy();

        // It is also important to consider accuracy difficulty when doing that.
        aimValue *= 0.98 + Math.pow(difficultyAttributes.overallDifficulty, 2) / 2500;

        return aimValue;
    }

    private double calculateSpeedValue() {

        double speedValue = Math.pow(5 * Math.max(1, difficultyAttributes.speedDifficulty / 0.0675) - 4, 3) / 100000;

        // Longer maps are worth more
        double lengthBonus = 0.95 + 0.4 * Math.min(1, getTotalHits() / 2000d);
        if (getTotalHits() > 2000) {
            lengthBonus += Math.log10(getTotalHits() / 2000d) * 0.5;
        }

        speedValue *= lengthBonus;

        if (effectiveMissCount > 0) {
            // Penalize misses by assessing # of misses relative to the total # of objects. Default a 3% reduction for any # of misses.
            speedValue *= 0.97 * Math.pow(1 - Math.pow(effectiveMissCount / getTotalHits(), 0.775), Math.pow(effectiveMissCount, 0.875));
        }

        speedValue *= getComboScalingFactor();

        // AR scaling
        if (difficultyAttributes.approachRate > 10.33) {
            // Buff for longer maps with high AR.
            speedValue *= 1 + 0.3 * (difficultyAttributes.approachRate - 10.33) * lengthBonus;
        }

        if (difficultyAttributes.mods.contains(GameMod.MOD_HIDDEN)) {
            speedValue *= 1 + 0.04 * (12 - difficultyAttributes.approachRate);
        }

        // Calculate accuracy assuming the worst case scenario.
        double relevantTotalDiff = getTotalHits() - difficultyAttributes.speedNoteCount;
        double relevantCountGreat = Math.max(0, countGreat - relevantTotalDiff);
        double relevantCountOk = Math.max(0, countOk - Math.max(0, relevantTotalDiff - countGreat));
        double relevantCountMeh = Math.max(0, countMeh - Math.max(0, relevantTotalDiff - countGreat - countOk));
        double relevantAccuracy = difficultyAttributes.speedNoteCount == 0 ? 0 : (relevantCountGreat * 6 + relevantCountOk * 2 + relevantCountMeh) / (difficultyAttributes.speedNoteCount * 6);

        // Scale the speed value with accuracy and OD.
        speedValue *= (0.95 + Math.pow(difficultyAttributes.overallDifficulty, 2) / 750) * Math.pow((getAccuracy() + relevantAccuracy) / 2, (14.5 - Math.max(difficultyAttributes.overallDifficulty, 8)) / 2);

        // Scale the speed value with # of 50s to punish double-tapping.
        speedValue *= Math.pow(0.99, Math.max(0, countMeh - getTotalHits() / 500d));

        return speedValue;
    }

    private double calculateAccuracyValue() {

        // This percentage only considers HitCircles of any value - in this part of the calculation we focus on hitting the timing hit window.
        double betterAccuracyPercentage = 0;
        int circleCount = difficultyAttributes.hitCircleCount;

        if (circleCount > 0) {
            betterAccuracyPercentage = Math.max(0, ((countGreat - (getTotalHits() - circleCount)) * 6 + countOk * 2 + countMeh) / (circleCount * 6d));
        }

        // Lots of arbitrary values from testing.
        // Considering to use derivation from perfect accuracy in a probabilistic manner - assume normal distribution
        double accuracyValue = Math.pow(1.52163, difficultyAttributes.overallDifficulty) * Math.pow(betterAccuracyPercentage, 24) * 2.83;

        // Bonus for many hit circles - it's harder to keep good accuracy up for longer
        accuracyValue *= Math.min(1.15, Math.pow(circleCount / 1000d, 0.3));

        if (difficultyAttributes.mods.contains(GameMod.MOD_HIDDEN)) {
            accuracyValue *= 1.08;
        }
        if (difficultyAttributes.mods.contains(GameMod.MOD_FLASHLIGHT)) {
            accuracyValue *= 1.02;
        }

        // Since most relax players wanted to include the accuracy value, we debuff the accuracy pp value by 31%
        if (difficultyAttributes.mods.contains(GameMod.MOD_RELAX)) {
            accuracyValue *= 0.695 + (difficultyAttributes.approachRate * 0.005);
        } 

        // Multiply the accuracy pp by 75% with the precise mod
        if (difficultyAttributes.mods.contains(GameMod.MOD_PRECISE)) {
            accuracyValue *= 1.75;
        }

        return accuracyValue;
    }

    private double calculateFlashlightValue() {
        if (!difficultyAttributes.mods.contains(GameMod.MOD_FLASHLIGHT)) {
            return 0;
        }

        double flashlightValue = Math.pow(difficultyAttributes.flashlightDifficulty, 2) * 25;

        if (effectiveMissCount > 0) {
            // Penalize misses by assessing # of misses relative to the total # of objects. Default a 3% reduction for any # of misses.
            flashlightValue *= 0.97 * Math.pow(1 - Math.pow(effectiveMissCount / getTotalHits(), 0.775), Math.pow(effectiveMissCount, 0.875));
        }

        // Since that flashlight is only for players who can memorize various beatmaps, we buff the value by 5%
        if (difficultyAttributes.mods.contains(GameMod.MOD_RELAX)) {
            flashlightValue *= 1.05;
        }

        flashlightValue *= getComboScalingFactor();

        // Account for shorter maps having a higher ratio of 0 combo/100 combo flashlight radius.
        flashlightValue *= 0.7 + 0.1 * Math.min(1, getTotalHits() / 200) +
                (getTotalHits() > 200 ? 0.2 * Math.min(1, (getTotalHits() - 200) / 200) : 0);

        // Scale the flashlight value with accuracy slightly.
        flashlightValue *= 0.5 + getAccuracy() / 2;

        // It is also important to consider accuracy difficulty when doing that.
        flashlightValue *= 0.98 + Math.pow(difficultyAttributes.overallDifficulty, 2) / 2500;

        return flashlightValue;
    }

    private double calculateEffectiveMissCount() {
        // Guess the number of misses + slider breaks from combo
        double comboBasedMissCount = 0;

        if (difficultyAttributes.sliderCount > 0) {
            double fullComboThreshold = difficultyAttributes.maxCombo - 0.1 * difficultyAttributes.sliderCount;

            if (scoreMaxCombo < fullComboThreshold) {
                // Clamp miss count to maximum amount of possible breaks.
                comboBasedMissCount = Math.min(
                        fullComboThreshold / Math.max(1, scoreMaxCombo),
                        countOk + countMeh + countMiss
                );
            }
        }

        return Math.max(countMiss, comboBasedMissCount);
    }

    private double getComboScalingFactor() {
        return difficultyAttributes.maxCombo <= 0 ? 0 : Math.min(Math.pow(scoreMaxCombo, 0.8) / Math.pow(difficultyAttributes.maxCombo, 0.8), 1);
    }
            }
